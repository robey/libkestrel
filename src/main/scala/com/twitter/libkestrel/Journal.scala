package com.twitter.libkestrel

import com.twitter.concurrent.Serialized
import com.twitter.conversions.storage._
import com.twitter.logging.Logger
import com.twitter.util._
import java.io.{File, FileOutputStream, IOException}
import scala.annotation.tailrec
import scala.collection.immutable
import scala.collection.mutable

case class FileInfo(file: File, headId: Long, tailId: Long, items: Int, bytes: Long)

object Journal {
  def getQueueNamesFromFolder(path: File): Set[String] = {
    path.list().filter { name =>
      !(name contains "~~")
    }.map { name =>
      name.split('.')(0)
    }.toSet
  }

  def builder(queuePath: File, timer: Timer, syncJournal: Duration, saveArchivedJournals: Option[File]) = {
    (queueName: String, maxFileSize: StorageUnit) => {
      new Journal(queuePath, queueName, maxFileSize, timer, syncJournal, saveArchivedJournals)
    }
  }

  def builder(queuePath: File, maxFileSize: StorageUnit, timer: Timer, syncJournal: Duration, saveArchivedJournals: Option[File]) = {
    (queueName: String) => {
      new Journal(queuePath, queueName, maxFileSize, timer, syncJournal, saveArchivedJournals)
    }
  }
}

/**
 * Maintain a set of journal files with the same prefix (`queuePath`/`queueName`):
 *   - list of adds (<prefix>.<timestamp>)
 *   - one state file for each reader (<prefix>.read.<name>)
 * The files filled with adds will be chunked as they reach `maxFileSize` in length.
 */
class Journal(
  queuePath: File,
  queueName: String,
  maxFileSize: StorageUnit,
  timer: Timer,
  syncJournal: Duration,
  saveArchivedJournals: Option[File]
) extends Serialized {
  private[this] val log = Logger.get(getClass)

  val prefix = new File(queuePath, queueName)

  @volatile var idMap = immutable.TreeMap.empty[Long, FileInfo]
  @volatile var readerMap = immutable.Map.empty[String, Reader]

  @volatile private[this] var _journalFile: JournalFile = null
  @volatile private[this] var _tailId = 0L

  // items & bytes in the current journal file so far:
  @volatile private[this] var currentItems = 0
  @volatile private[this] var currentBytes = 0L

  removeTemporaryFiles()
  buildIdMap()
  openJournal()
  buildReaderMap()

  // make sure there's always at least a default reader.
  if (readerMap.isEmpty) reader("")

  // and there's no default reader if there's at least one other one.
  if (readerMap.size > 1) {
    readerMap.get("").foreach { r =>
      r.file.delete()
      readerMap = readerMap - ""
    }
  }

  /**
   * Scan timestamp files for this queue, and build a map of (item id -> file) for the first id
   * seen in each file. This lets us quickly find the right file when we look for an item id.
   */
  private[this] def buildIdMap() {
    var newMap = immutable.TreeMap.empty[Long, FileInfo]
    writerFiles().foreach { file =>
      scanJournalFile(file).foreach { fileInfo =>
        newMap += (fileInfo.headId -> fileInfo)
      }
    }
    idMap = newMap
  }

  def removeTemporaryFiles() {
    queuePath.list().foreach { name =>
      if (name contains "~~") new File(queuePath, name).delete()
    }
  }

  def writerFiles() = {
    queuePath.list().filter { name =>
      name.startsWith(queueName + ".") &&
        !name.contains("~") &&
        !name.split("\\.")(1).find { !_.isDigit }.isDefined
    }.map { name =>
      new File(queuePath, name)
    }
  }

  def readerFiles() = {
    queuePath.list().filter { name =>
      name.startsWith(queueName + ".read.") && !name.contains("~")
    }.map { name =>
      new File(queuePath, name)
    }
  }

  def fileInfoForId(id: Long): Option[FileInfo] = {
    idMap.to(id).lastOption.map { case (k, v) => v }
  }

  def fileInfosAfter(id: Long): Seq[FileInfo] = {
    idMap.from(id).values.toSeq
  }

  private[this] def buildReaderMap() {
    var newMap = immutable.HashMap.empty[String, Reader]
    readerFiles().foreach { file =>
      val name = file.getName.split("\\.", 3)(2)
      try {
        val reader = new Reader(name, file)
        reader.readState()
        newMap = newMap + (name -> reader)
      } catch {
        case e: IOException => log.warning("Skipping corrupted reader file: %s", file)
      }
    }
    readerMap = newMap
  }

  // find the earliest possible head id
  private[this] def earliestHead = {
    if (idMap.size == 0) {
      0L
    } else {
      idMap.head match { case (id, file) => id }
    }
  }

  // scan a journal file to pull out the # items, # bytes, and head & tail ids.
  private[this] def scanJournalFile(file: File): Option[FileInfo] = {
    var firstId: Option[Long] = None
    var tailId = 0L
    var items = 0
    var bytes = 0L
    val journalFile = try {
      JournalFile.openWriter(file, timer, syncJournal)
    } catch {
      case e: IOException => {
        log.error(e, "Unable to open journal %s; aborting!", file)
        return None
      }
    }

    try {
      log.info("Scanning journal '%s' file %s", queueName, file)
      journalFile.foreach { entry =>
        val position = journalFile.position
        entry match {
          case JournalFile.Record.Put(item) => {
            if (firstId == None) firstId = Some(item.id)
            items += 1
            bytes += item.data.size
            tailId = item.id
          }
          case _ =>
        }
      }
      journalFile.close()
    } catch {
      case e @ CorruptedJournalException(position, file, message) => {
        log.error("Corrupted journal %s at position %d; truncating. DATA MAY HAVE BEEN LOST!",
          file, position)
        journalFile.close()
        val trancateWriter = new FileOutputStream(file, true).getChannel
        try {
          trancateWriter.truncate(position)
        } finally {
          trancateWriter.close()
        }
        // try again on the truncated file.
        return scanJournalFile(file)
      }
    }
    if (firstId == None) {
      // not a single thing in this journal file.
      log.info("Empty journal file %s -- erasing.", file)
      file.delete()
      None
    } else {
      firstId.map { id => FileInfo(file, id, tailId, items, bytes) }
    }
  }

  private[this] def openJournal() {
    if (idMap.size > 0) {
      val (id, fileInfo) = idMap.last
      try {
        _journalFile = JournalFile.append(fileInfo.file, timer, syncJournal)
        _tailId = fileInfo.tailId
        currentItems = fileInfo.items
        currentBytes = fileInfo.bytes
      } catch {
        case e: IOException => {
          log.error("Unable to open journal %s; aborting!", fileInfo.file)
          throw e
        }
      }
    } else {
      log.info("No transaction journal for '%s'; starting with empty queue.", queueName)
      rotate()
    }
  }

  private[this] def uniqueFile(prefix: File): File = {
    var file = new File(prefix.getAbsolutePath + Time.now.inMilliseconds)
    while (!file.createNewFile()) {
      Thread.sleep(1)
      file = new File(prefix.getAbsolutePath + Time.now.inMilliseconds)
    }
    file
  }

  // delete any journal files that are unreferenced.
  private[this] def checkOldFiles() {
    val minHead = readerMap.values.foldLeft(tail) { (n, r) => n min (r.head + 1) }
    // all the files that start off with unreferenced ids, minus the last. :)
    idMap.takeWhile { case (id, fileInfo) => id <= minHead }.dropRight(1).foreach { case (id, fileInfo) =>
      log.info("Erasing unused journal file for '%s': %s", queueName, fileInfo.file)
      idMap = idMap - id
      if (saveArchivedJournals.isDefined) {
        val archiveFile = new File(saveArchivedJournals.get, "archive~" + fileInfo.file.getName)
        fileInfo.file.renameTo(archiveFile)
      } else {
        fileInfo.file.delete()
      }
    }
  }

  private[this] def rotate() {
    if (_journalFile ne null) {
      // fix up id map to have the new item/byte count
      idMap.last match { case (id, info) =>
        idMap += (id -> FileInfo(_journalFile.file, id, _tailId, currentItems, currentBytes))
      }
    }
    // open new file
    var newFile = uniqueFile(new File(queuePath, queueName + "."))
    if (_journalFile eq null) {
      log.info("Rotating %s to %s", queueName, newFile)
    } else {
      log.info("Rotating %s from %s (%s) to %s", queueName, _journalFile.file,
        _journalFile.position.bytes.toHuman, newFile)
    }
    _journalFile = JournalFile.createWriter(newFile, timer, syncJournal)
    currentItems = 0
    currentBytes = 0
    idMap += (_tailId + 1 -> FileInfo(newFile, _tailId + 1, 0, 0, 0L))
    checkOldFiles()
  }

  // warning: set up your chaining before calling this. _tailId could increment during this method.
  def reader(name: String): Reader = {
    readerMap.get(name).getOrElse {
      // grab a lock so only one thread does this potentially slow thing at once
      synchronized {
        readerMap.get(name).getOrElse {
          val file = new File(queuePath, queueName + ".read." + name)
          val reader = readerMap.get("") match {
            case Some(r) => {
              // move the default reader over to our new one.
              val oldFile = r.file
              r.file = file
              r.name = name
              r.checkpoint()
              oldFile.delete()
              readerMap = readerMap - ""
              r
            }
            case None => {
              val reader = new Reader(name, file)
              reader.head = _tailId
              reader.checkpoint()
              reader
            }
          }
          readerMap = readerMap + (name -> reader)
          reader
        }
      }
    }
  }

  def journalSize: Long = {
    writerFiles().foldLeft(0L) { (sum, file) => sum + file.length() }
  }

  def tail = _tailId

  def close() {
    readerMap.values.foreach { reader =>
      reader.checkpoint()
      reader.close()
    }
    readerMap = immutable.Map.empty[String, Reader]
    _journalFile.close()
  }

  /**
   * Get rid of all journal files for this queue.
   */
  def erase() {
    close()
    readerFiles().foreach { _.delete() }
    writerFiles().foreach { _.delete() }
    removeTemporaryFiles()
  }

  def checkpoint(): Future[Unit] = {
    val futures = readerMap.map { case (name, reader) =>
      reader.checkpoint()
    }
    serialized {
      checkOldFiles()
    }
    Future.join(futures.toSeq)
  }

  def put(data: Array[Byte], addTime: Time, expireTime: Option[Time], f: QueueItem => Unit = { _ => }): Future[(QueueItem, Future[Unit])] = {
    serialized {
      _tailId += 1
      val id = _tailId
      val item = QueueItem(_tailId, addTime, expireTime, data)
      val future = _journalFile.put(item)
      currentItems += 1
      currentBytes += data.size
      if (_journalFile.position >= maxFileSize.inBytes) rotate()
      // give the caller a chance to run some other code serialized:
      f(item)
      (item, future)
    }
  }

  /**
   * Track state for a queue reader. Every item prior to the "head" pointer (including the "head"
   * pointer itself) has been read by this reader. Separately, "doneSet" is a set of items that
   * have been read out of order, usually because they refer to transactional reads that were
   * confirmed out of order.
   */
  case class Reader(_name: String, _file: File) extends Serialized {
    @volatile var file: File = _file
    @volatile var name: String = _name

    private[this] var _head = 0L
    private[this] val _doneSet = new ItemIdList()
    private[this] var readBehind: Option[Scanner] = None

    def readState() {
      val journalFile = JournalFile.openReader(file, timer, syncJournal)
      try {
        journalFile.foreach { entry =>
          entry match {
            case JournalFile.Record.ReadHead(id) => _head = id
            case JournalFile.Record.ReadDone(ids) => _doneSet.add(ids.filter { _ <= _tailId })
            case x => log.warning("Skipping unknown entry %s in read journal: %s", x, file)
          }
        }
      } finally {
        journalFile.close()
      }
      _head = (_head min _tailId) max (earliestHead - 1)
      log.debug("Read checkpoint %s+%s: head=%s done=(%s)", queueName, name, _head, _doneSet.toSeq.sorted.mkString(","))
    }

    /**
     * Rewrite the reader file with the current head and out-of-order committed reads.
     */
    def checkpoint(): Future[Unit] = {
      val head = _head
      val doneSet = _doneSet.toSeq
      // FIXME really this should go in another thread. doesn't need to happen inline.
      serialized {
        log.debug("Checkpoint %s+%s: head=%s done=(%s)", queueName, name, head, doneSet.sorted.mkString(","))
        val newFile = uniqueFile(new File(file.getParent, file.getName + "~~"))
        val newJournalFile = JournalFile.createReader(newFile, timer, syncJournal)
        newJournalFile.readHead(_head)
        newJournalFile.readDone(_doneSet.toSeq)
        newJournalFile.close()
        newFile.renameTo(file)
      }
    }

    def head: Long = this._head
    def doneSet: Set[Long] = _doneSet.toSeq.toSet
    def tail: Long = Journal.this._tailId

    def head_=(id: Long) {
      _head = id
      val toRemove = _doneSet.toSeq.filter { _ <= _head }
      _doneSet.remove(toRemove.toSet)
    }

    def commit(id: Long) {
      if (id == _head + 1) {
        _head += 1
        while (_doneSet contains _head + 1) {
          _head += 1
          _doneSet.remove(_head)
        }
      } else {
        _doneSet.add(id)
      }
    }

    /**
     * Discard all items and catch up with the main queue.
     */
    def flush() {
      _head = _tailId
      _doneSet.popAll()
      endReadBehind()
    }

    def close() {
      endReadBehind()
    }

    def inReadBehind = readBehind.isDefined

    def readBehindId = readBehind.get.id

    /**
     * Open the journal file containing a given item, so we can read items directly out of the
     * file. This means the queue no longer wants to try keeping every item in memory.
     */
    def startReadBehind(id: Long) {
      readBehind = Some(new Scanner(id, logIt = true))
    }

    /**
     * Read & return the next item in the read-behind journals.
     * If we've caught up, turn off read-behind and return None.
     */
    def nextReadBehind(): Option[QueueItem] = {
      val rv = readBehind.get.next()
      if (rv == None) readBehind = None
      rv
    }

    /**
     * End read-behind mode, and close any open journal file.
     */
    def endReadBehind() {
      readBehind.foreach { _.end() }
      readBehind = None
    }

    /**
     * Scan forward through journals from a specific starting point.
     */
    class Scanner(startId: Long, followFiles: Boolean = true, logIt: Boolean = false) {
      private[this] var journalFile: JournalFile = _
      var id = 0L

      start()

      def start() {
        val fileInfo = fileInfoForId(startId).getOrElse { idMap(earliestHead) }
        val jf = JournalFile.openWriter(fileInfo.file, timer, syncJournal)
        if (startId >= earliestHead) {
          var lastId = -1L
          while (lastId < startId) {
            jf.readNext() match {
              case None => {
                // just end read-behind immediately.
                id = tail
                return
              }
              case Some(JournalFile.Record.Put(QueueItem(id, _, _, _))) => lastId = id
              case _ =>
            }
          }
        }
        journalFile = jf
        id = startId
      }

      @tailrec
      final def next(): Option[QueueItem] = {
        if (id == tail) {
          end()
          return None
        }
        journalFile.readNext() match {
          case None => {
            journalFile.close()
            if (followFiles) {
              val fileInfo = fileInfoForId(id + 1)
              if (!fileInfo.isDefined) throw new IOException("Unknown id")
              if (logIt) log.debug("Read-behind for %s+%s moving to: %s", queueName, name, fileInfo.get.file)
              journalFile = JournalFile.openWriter(fileInfo.get.file, timer, syncJournal)
              next()
            } else {
              end()
              None
            }
          }
          case Some(JournalFile.Record.Put(item)) => {
            id = item.id
            Some(item)
          }
          case _ => next()
        }
      }

      def end() {
        if (logIt) log.info("Leaving read-behind for %s+%s", queueName, name)
        if (journalFile ne null) journalFile.close()
      }
    }
  }
}