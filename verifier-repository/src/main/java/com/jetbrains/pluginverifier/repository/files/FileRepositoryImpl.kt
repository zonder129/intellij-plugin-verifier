package com.jetbrains.pluginverifier.repository.files

import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.jetbrains.pluginverifier.misc.createDir
import com.jetbrains.pluginverifier.misc.deleteLogged
import com.jetbrains.pluginverifier.misc.pluralize
import com.jetbrains.pluginverifier.repository.cleanup.*
import com.jetbrains.pluginverifier.repository.downloader.DownloadExecutor
import com.jetbrains.pluginverifier.repository.downloader.DownloadResult
import com.jetbrains.pluginverifier.repository.downloader.Downloader
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class FileRepositoryImpl<K>(repositoryDir: Path,
                            fileNameMapper: FileNameMapper<K>,
                            downloader: Downloader<K>,
                            private val sweepPolicy: SweepPolicy<K>,
                            private val clock: Clock = Clock.systemUTC()) : FileRepository<K> {

  companion object {
    private val LOG: Logger = LoggerFactory.getLogger(FileRepositoryImpl::class.java)

    private val LOCK_TIME_TO_LIVE_DURATION: Duration = Duration.of(1, ChronoUnit.HOURS)

    fun <K> createFromExistingFiles(repositoryDir: Path,
                                    downloader: Downloader<K>,
                                    fileNameMapper: FileNameMapper<K>,
                                    sweepPolicy: SweepPolicy<K>,
                                    clock: Clock = Clock.systemUTC(),
                                    keyProvider: (Path) -> K? = { null }): FileRepositoryImpl<K> {
      val fileRepository = FileRepositoryImpl(repositoryDir, fileNameMapper, downloader, sweepPolicy, clock)
      addInitiallyAvailableFiles(fileRepository, repositoryDir, keyProvider)
      fileRepository.sweep()
      return fileRepository
    }

    private fun <K> addInitiallyAvailableFiles(fileRepository: FileRepository<K>,
                                               repositoryDir: Path,
                                               keyProvider: (Path) -> K?) {
      val existingFiles = Files.list(repositoryDir) ?: throw IOException("Unable to read directory content: $repositoryDir")
      for (file in existingFiles) {
        val key = keyProvider(file)
        if (key != null) {
          fileRepository.add(key, file)
        }
      }
    }

  }

  private data class RepositoryFilesRegistrar<K>(var totalSpaceUsage: SpaceAmount = SpaceAmount.ZERO_SPACE,
                                                 val files: MutableMap<K, FileInfo> = hashMapOf()) {
    fun addFile(key: K, file: Path) {
      assert(key !in files)
      val fileSize = file.fileSize
      LOG.debug("Adding file by $key of size $fileSize: $file")
      totalSpaceUsage += fileSize
      files[key] = FileInfo(file, fileSize)
    }

    fun getAllKeys() = files.keys

    fun has(key: K) = key in files

    fun get(key: K) = files[key]

    fun deleteFile(key: K) {
      assert(key in files)
      val (file, size) = files[key]!!
      LOG.debug("Deleting file by $key of size $size: $file")
      totalSpaceUsage -= size
      files.remove(key)
      file.deleteLogged()
    }
  }

  private val filesRegistrar = RepositoryFilesRegistrar<K>()

  private var nextLockId: Long = 0

  private val key2Locks = hashMapOf<K, MutableSet<FileLock>>()

  private val deleteQueue = hashSetOf<K>()

  private val downloading = hashMapOf<K, FutureTask<DownloadResult>>()

  private val statistics = hashMapOf<K, UsageStatistic>()

  private val downloadExecutor = DownloadExecutor(repositoryDir, downloader, fileNameMapper)

  init {
    repositoryDir.createDir()
    runForgottenLocksInspector()
  }

  private fun runForgottenLocksInspector() {
    Executors.newSingleThreadScheduledExecutor(
        ThreadFactoryBuilder()
            .setDaemon(true)
            .build()
    ).scheduleAtFixedRate({ detectForgottenLocks() }, 1, 60, TimeUnit.MINUTES)
  }

  @Synchronized
  override fun add(key: K, file: Path): Boolean {
    if (filesRegistrar.has(key)) {
      return false
    }
    assert(key !in statistics)
    filesRegistrar.addFile(key, file)
    statistics[key] = UsageStatistic(Instant.EPOCH, 0)
    return true
  }

  @Synchronized
  override fun <R> lockAndAccess(block: () -> R): R = block()

  @Synchronized
  override fun getAllExistingKeys() = filesRegistrar.getAllKeys()

  @Synchronized
  override fun has(key: K) = filesRegistrar.has(key)

  @Synchronized
  override fun remove(key: K): Boolean {
    val isLocked = isLockedKey(key)
    return if (isLocked) {
      LOG.debug("Deletion of $key: file is locked or is being downloaded, delete later.")
      deleteQueue.add(key)
      false
    } else {
      LOG.debug("Deletion of $key: non-locked, delete now")
      doRemove(key)
      true
    }
  }

  @Synchronized
  private fun isLockedKey(key: K) = key2Locks[key].orEmpty().isNotEmpty()

  @Synchronized
  private fun registerLock(key: K, isDownloadingLock: Boolean): FileLockImpl<K> {
    val fileInfo = if (isDownloadingLock) {
      //Indicates that the file is being downloaded. It is never accessed.
      FileInfo(Paths.get("Downloading"), SpaceAmount.ZERO_SPACE)
    } else {
      assert(filesRegistrar.has(key))
      filesRegistrar.get(key)!!
    }
    val lockTime = clock.instant()
    val lock = FileLockImpl(fileInfo.file, lockTime, key, nextLockId++, this)
    key2Locks.getOrPut(key, { hashSetOf() }).add(lock)

    if (!isDownloadingLock) {
      val keyUsageStatistic = statistics.getOrPut(key, { UsageStatistic(lockTime, 0) })
      keyUsageStatistic.timesAccessed++
    }
    return lock
  }

  @Synchronized
  internal fun releaseLock(lock: FileLockImpl<K>) {
    val key = lock.key
    val fileLocks = key2Locks[key]
    if (fileLocks != null) {
      fileLocks.remove(lock)
      if (fileLocks.isEmpty()) {
        key2Locks.remove(key)
        if (key in deleteQueue) {
          deleteQueue.remove(key)
          doRemove(key)
        }
      }
    }
  }

  @Synchronized
  private fun doRemove(key: K) {
    assert(key !in downloading)
    filesRegistrar.deleteFile(key)
    statistics.remove(key)
  }

  private fun downloadOrWait(key: K): FileRepositoryResult {
    val (downloadTask, runInCurrentThread, waitingLock) = synchronized(this) {
      val waitingLock = registerLock(key, true)
      val existingTask = downloading[key]
      if (existingTask != null) {
        Triple(existingTask, false, waitingLock)
      } else {
        val downloadTask = FutureTask {
          downloadAndAddFile(key)
        }
        downloading[key] = downloadTask
        Triple(downloadTask, true, waitingLock)
      }
    }

    //Run the downloading task if the current thread has initialized it.
    if (runInCurrentThread) {
      downloadTask.run()
    }

    try {
      val downloadResult = downloadTask.get()
      return downloadResult.toFileRepositoryResult(key)
    } finally {
      waitingLock.release()
      if (runInCurrentThread) {
        synchronized(this) {
          downloading.remove(key)
        }
      }
    }
  }

  private fun downloadAndAddFile(key: K): DownloadResult {
    val downloadResult = downloadExecutor.download(key)
    if (downloadResult is DownloadResult.Downloaded) {
      add(key, downloadResult.downloadedFileOrDirectory)
    }
    return downloadResult
  }

  private fun DownloadResult.toFileRepositoryResult(key: K) = when (this) {
    is DownloadResult.Downloaded -> FileRepositoryResult.Found(registerLock(key, false))
    is DownloadResult.NotFound -> FileRepositoryResult.NotFound(reason)
    is DownloadResult.FailedToDownload -> FileRepositoryResult.Failed(reason, error)
  }

  @Synchronized
  private fun lockFileIfExists(key: K): FileLockImpl<K>? {
    if (filesRegistrar.has(key)) {
      return registerLock(key, false)
    }
    return null
  }

  @Synchronized
  private fun detectForgottenLocks() {
    for ((key, locks) in key2Locks) {
      for (lock in locks) {
        val now = clock.instant()
        val lockTime = lock.lockTime
        val maxUnlockTime = lockTime.plus(LOCK_TIME_TO_LIVE_DURATION)
        val isForgotten = now.isAfter(maxUnlockTime)
        if (isForgotten) {
          LOG.warn("Forgotten lock found for $key on ${lock.file}; lock date = $lockTime")
        }
      }
    }
  }

  @Synchronized
  override fun sweep() {
    if (sweepPolicy.isNecessary(filesRegistrar.totalSpaceUsage)) {
      val availableFiles = filesRegistrar.files.map { (key, fileInfo) ->
        AvailableFile(key, fileInfo, statistics[key]!!, isLockedKey(key))
      }

      val sweepInfo = SweepInfo(filesRegistrar.totalSpaceUsage, availableFiles)
      val filesForDeletion = sweepPolicy.selectFilesForDeletion(sweepInfo)

      if (filesForDeletion.isNotEmpty()) {
        val deletionsSize = filesForDeletion.map { it.fileInfo.size }.reduce { acc, spaceAmount -> acc + spaceAmount }
        LOG.info("It's time to remove unused files. " +
            "Space usage: ${filesRegistrar.totalSpaceUsage}. " +
            "${filesForDeletion.size} " + "file".pluralize(filesForDeletion.size) +
            " will be removed having total size $deletionsSize"
        )
        for (availableFile in filesForDeletion) {
          remove(availableFile.key)
        }
      }
    }
  }

  /**
   * Searches the file by [key] in the local cache. If it isn't found there,
   * downloads the file.
   *
   * The possible results are represented as subclasses of [FileRepositoryResult].
   * If the file is found locally or successfully downloaded, the file lock is registered
   * for the file so it will be protected against deletions by other threads.
   *
   * This method is thread safe. In case several threads attempt to get the same file, only one
   * of them will download it while others will wait for the first to complete.
   */
  override fun get(key: K): FileRepositoryResult {
    val lockedFile = lockFileIfExists(key)
    val result = if (lockedFile != null) {
      FileRepositoryResult.Found(lockedFile)
    } else {
      downloadOrWait(key)
    }
    try {
      sweep()
    } catch (e: Throwable) {
      (result as? FileRepositoryResult.Found)?.lockedFile?.release()
      throw e
    }
    return result
  }

}