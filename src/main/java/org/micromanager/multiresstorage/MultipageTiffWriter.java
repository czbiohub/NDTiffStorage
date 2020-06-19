///////////////////////////////////////////////////////////////////////////////
//FILE:          MultipageTiffWriter.java
//PROJECT:       Micro-Manager
//SUBSYSTEM:     mmstudio
//-----------------------------------------------------------------------------
//
// AUTHOR:       Henry Pinkard, henry.pinkard@gmail.com, 2012
//
// COPYRIGHT:    University of California, San Francisco, 2012
//
// LICENSE:      This file is distributed under the BSD license.
//               License text is included with the source distribution.
//
//               This file is distributed in the hope that it will be useful,
//               but WITHOUT ANY WARRANTY; without even the implied warranty
//               of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//
//               IN NO EVENT SHALL THE COPYRIGHT OWNER OR
//               CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
//               INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.
//
package org.micromanager.multiresstorage;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import mmcorej.TaggedImage;
import mmcorej.org.json.JSONArray;
import mmcorej.org.json.JSONException;
import mmcorej.org.json.JSONObject;

import javax.swing.*;

public class MultipageTiffWriter {

//   private static final long BYTES_PER_MEG = 1048576;
//   private static final long MAX_FILE_SIZE = 15*BYTES_PER_MEG;
   private static final long BYTES_PER_GIG = 1073741824;
   private static final long MAX_FILE_SIZE = 4 * BYTES_PER_GIG;
   public static final int DISPLAY_SETTINGS_BYTES_PER_CHANNEL = 256;
   //1 MB for now...might have to increase
   public static final long SPACE_FOR_COMMENTS = 1048576;
   public static final int INDEX_MAP_OFFSET_HEADER = 54773648;
   public static final int INDEX_MAP_HEADER = 3453623;
   public static final int DISPLAY_SETTINGS_OFFSET_HEADER = 483765892;
   public static final int DISPLAY_SETTINGS_HEADER = 347834724;
   public static final int COMMENTS_OFFSET_HEADER = 99384722;
   public static final int COMMENTS_HEADER = 84720485;

   public static final char ENTRIES_PER_IFD = 13;
   //Required tags
   public static final char WIDTH = 256;
   public static final char HEIGHT = 257;
   public static final char BITS_PER_SAMPLE = 258;
   public static final char COMPRESSION = 259;
   public static final char PHOTOMETRIC_INTERPRETATION = 262;
   public static final char IMAGE_DESCRIPTION = 270;
   public static final char STRIP_OFFSETS = 273;
   public static final char SAMPLES_PER_PIXEL = 277;
   public static final char ROWS_PER_STRIP = 278;
   public static final char STRIP_BYTE_COUNTS = 279;
   public static final char X_RESOLUTION = 282;
   public static final char Y_RESOLUTION = 283;
   public static final char RESOLUTION_UNIT = 296;
   public static final char MM_METADATA = 51123;

   public static final int SUMMARY_MD_HEADER = 2355492;

   public static final ByteOrder BYTE_ORDER = ByteOrder.nativeOrder();

   private ResolutionLevel masterMPTiffStorage_;
   private RandomAccessFile raFile_;
   private FileChannel fileChannel_;
   private ExecutorService writingExecutor_;
   private long filePosition_ = 0;
   private long indexMapPosition_; //current position of the dynamically written index map
   private long indexMapFirstEntry_; // mark position of first entry so that number of entries can be written at end
   private int bufferPosition_;
   private ConcurrentHashMap<String, Long> indexMap_;
   private long nextIFDOffsetLocation_ = -1;
   private boolean rgb_ = false;
   private int byteDepth_, imageWidth_, imageHeight_, bytesPerImagePixels_;
   private long resNumerator_ = 1, resDenomenator_ = 1;
   private double zStepUm_ = 1;
   private LinkedList<ByteBuffer> buffers_;
   private boolean firstIFD_ = true;
   private long ijDescriptionTagPosition_;
   private long ijMetadataCountsTagPosition_;
   private long ijMetadataTagPosition_;
   //Reader associated with this file
   private MultipageTiffReader reader_;
   private final String filename_;
   private volatile boolean displayStorer_;

   public MultipageTiffWriter(String directory, String filename,
           JSONObject summaryMD, ResolutionLevel mpTiffStorage,
           boolean splitByPositions, ExecutorService writingExecutor,
           int imageWidth, int imageHeight, boolean rgb, int byteDepth) throws IOException {
      displayStorer_ = false;
      masterMPTiffStorage_ = mpTiffStorage;
      reader_ = new MultipageTiffReader(summaryMD, byteDepth);
      File f = new File(directory + "/" + filename);
      filename_ = directory + "/" + filename;

      imageWidth_ = imageWidth;
      imageHeight_ = imageHeight;
      rgb_ = rgb;
      byteDepth_ = byteDepth;
      bytesPerImagePixels_ = imageHeight_ * imageWidth_ * byteDepth_ * (rgb_ ? 3 : 1);

      //this is just for optional tiff stuff now
       processSummaryMD(summaryMD);
      
      //This is an overestimate of file size because file gets truncated at end
//      long fileSize = Math.min(MAX_FILE_SIZE, summaryMD.toString().length() + 2000000
//              + numFrames_ * numChannels_ * numSlices_ * ((long) bytesPerImagePixels_ + 2000));
      //just set it to the max, since we don't really know in advance how many frames there are, and
      //we dont want to slow down performance by continually making calls to the OS to expand the fie
      long fileSize = MAX_FILE_SIZE;

      f.createNewFile();
      raFile_ = new RandomAccessFile(f, "rw");
      try {
         raFile_.setLength(fileSize);
      } catch (IOException e) {
         new Thread(new Runnable() {

            @Override
            public void run() {
               try {
                  Thread.sleep(1000);
               } catch (InterruptedException ex) {
               }
            }
         }).start();
         JOptionPane.showMessageDialog(null, "Insufficient space on disk to write data",
                 "Error", JOptionPane.ERROR_MESSAGE);
         throw new RuntimeException("Insufficent space on disk: no room to write data");
      }
      fileChannel_ = raFile_.getChannel();
      writingExecutor_ = writingExecutor;
      indexMap_ = new ConcurrentHashMap<String, Long>();
      reader_.setFileChannel(fileChannel_);
      reader_.setIndexMap(indexMap_);
      buffers_ = new LinkedList<ByteBuffer>();

      writeMMHeaderAndSummaryMD(summaryMD);
   }


   //
   // Buffer allocation and recycling
   //

   // The idea here is to recycle the direct buffers for image pixels, because
   // allocation is slow. We do not need a large pool,
   // because the only aim is to avoid situations where allocation is limiting
   // at steady state. If writing is, on average, faster than incoming images,
   // the pool should always have a buffer ready for a new request.
   // Ideally we would also evict unused buffers after a timeout, so as not to
   // leak memory after writing has concluded.

   private static final int BUFFER_DIRECT_THRESHOLD = 1024;
   private static ByteBuffer allocateByteBuffer(int capacity) {
      ByteBuffer b = capacity >= BUFFER_DIRECT_THRESHOLD ?
              ByteBuffer.allocateDirect(capacity) :
              ByteBuffer.allocate(capacity);
      return b.order(BYTE_ORDER);
   }

   private static final int BUFFER_POOL_SIZE =
           System.getProperty("sun.arch.data.model").equals("32") ? 0 : 3;
   private static final Deque<ByteBuffer> pooledBuffers_;
   static {
      if (BUFFER_POOL_SIZE > 0) {
         pooledBuffers_ = new ArrayDeque<>(BUFFER_POOL_SIZE);
      }
      else {
         pooledBuffers_ = null;
      }
   }
   private static int pooledBufferCapacity_ = 0;

   private static ByteBuffer getLargeBuffer(int capacity) {
      if (BUFFER_POOL_SIZE == 0) {
         return allocateByteBuffer(capacity);
      }

      synchronized (MultipageTiffWriter.class) {
         if (capacity != pooledBufferCapacity_) {
            pooledBuffers_.clear();
            pooledBufferCapacity_ = capacity;
         }

         // Recycle in LIFO order (smaller images may still be in L3 cache)
         ByteBuffer b = pooledBuffers_.pollFirst();
         if (b != null) {
            // Ensure correct byte order in case recycled from other source
            b.order(BYTE_ORDER).clear();
            return b;
         }
      }
      return allocateByteBuffer(capacity);
   }

   private static void tryRecycleLargeBuffer(ByteBuffer b) {
      // Keep up to BUFFER_POOL_SIZE direct buffers of the current size
      if (BUFFER_POOL_SIZE == 0 || !b.isDirect()) {
         return;
      }
      synchronized (MultipageTiffWriter.class) {
         if (b.capacity() == pooledBufferCapacity_) {
            if (pooledBuffers_.size() == BUFFER_POOL_SIZE) {
               pooledBuffers_.removeLast(); // Discard oldest
            }
            pooledBuffers_.addFirst(b);
         }
      }
   }


   private Future executeWritingTask(Runnable writingTask) {
      return writingExecutor_.submit(writingTask);
   }

   private Future fileChannelWrite(final Buffer buffer, final long position) {
      return executeWritingTask(
              new Runnable() {
         @Override
         public void run() {
            try {
               buffer.rewind();
               fileChannel_.write((ByteBuffer) buffer, position);
//               fileChannel_.force(false);
            } catch (ClosedChannelException e) {
               throw new RuntimeException(e);
            } catch (IOException e) {
               throw new RuntimeException(e);
            }
            tryRecycleLargeBuffer((ByteBuffer) buffer);
         }
      });
   }

   private Future fileChannelWrite(final ByteBuffer[] buffers) {
      return executeWritingTask(
              new Runnable() {
         @Override
         public void run() {

            try {
               fileChannel_.write(buffers);
            } catch (IOException e) {
               throw new RuntimeException(e);
            }
            for (ByteBuffer buffer : buffers) {
               tryRecycleLargeBuffer(buffer);
            }
         }
      });
   }

   public MultipageTiffReader getReader() {
      return reader_;
   }

   public ConcurrentHashMap<String, Long> getIndexMap() {
      return indexMap_;
   }

   private void writeMMHeaderAndSummaryMD(JSONObject summaryMD) throws IOException {
      if (summaryMD.has("Comment")) {
         summaryMD.remove("Comment");
      }
      byte[] summaryMDBytes = getBytesFromString(summaryMD.toString());
      int mdLength = summaryMDBytes.length;
      //20 bytes plus 8 header for index map
      long maxImagesInFile = MAX_FILE_SIZE / bytesPerImagePixels_;
      long indexMapSpace = 8 + 20 * maxImagesInFile;

      ByteBuffer headerBuffer = allocateByteBuffer(40);
      //8 bytes for file header
      if (BYTE_ORDER.equals(ByteOrder.BIG_ENDIAN)) {
         headerBuffer.asCharBuffer().put(0, (char) 0x4d4d);
      } else {
         headerBuffer.asCharBuffer().put(0, (char) 0x4949);
      }
      headerBuffer.asCharBuffer().put(1, (char) 42);
      int firstIFDOffset = 40 + (int) (mdLength + indexMapSpace);
      if (firstIFDOffset % 2 == 1) {
         firstIFDOffset++; //Start first IFD on a word
      }
      headerBuffer.putInt(4, firstIFDOffset);

      //8 bytes for index map offset header and offset
      headerBuffer.putInt(8, INDEX_MAP_OFFSET_HEADER);
      headerBuffer.putInt(12, headerBuffer.capacity() + mdLength);

      //8 bytes for display settings offset header and display settings offset--written later
      //8 bytes for comments offset header and comments offset--written later
      //8 bytes for summaryMD header  summary md length + 
      headerBuffer.putInt(32, SUMMARY_MD_HEADER);
      headerBuffer.putInt(36, mdLength);

      ByteBuffer indexMapBuffer = allocateByteBuffer((int) indexMapSpace);
      indexMapBuffer.putInt(0, INDEX_MAP_HEADER);
      indexMapBuffer.putInt(4, (int) maxImagesInFile);
      indexMapPosition_ = headerBuffer.capacity() + mdLength + 8;
      indexMapFirstEntry_ = indexMapPosition_;

      //1 byte for each byte of UTF-8-encoded summary md
      ByteBuffer[] buffers = new ByteBuffer[3];
      buffers[0] = headerBuffer;
      buffers[1] = ByteBuffer.wrap(summaryMDBytes);
      buffers[2] = indexMapBuffer;

      fileChannelWrite(buffers);
      filePosition_ += headerBuffer.capacity() + mdLength + indexMapSpace;

      if (filePosition_ % 2 == 1) {
         filePosition_++;
         executeWritingTask(new Runnable() {
            @Override
            public void run() {
               try {
                  fileChannel_.position(fileChannel_.position() + 1);
               } catch (IOException ex) {
                  throw new RuntimeException("Couldn't reposition file channel");
               }
            }
         });
      }
   }

   /**
    * Called when there is no more data to be written. Write null offset after
    * last image in accordance with TIFF specification and set number of index
    * map entries for backwards reading capability A file that has been finished
    * should have everything it needs to be properly reopened in MM or by a
    * basic TIFF reader
    */
   public void finishedWriting() throws IOException, ExecutionException, InterruptedException {
      writeNullOffsetAfterLastImage();
      //go back to the index map header and change the number of entries from the max
      //value allotted early to the actual number written
      //The MultipageTiffReader no longer needs this because it interperets 0's as the 
      //the end of the index map. It is added here for backwards compatibility of reading
      //using versions of MM before 6-6-2014. Without it, old versions wouldn't correctly read image 0_0_0_0
      int numImages = (int) ((indexMapPosition_ - indexMapFirstEntry_) / 20);
      ByteBuffer indexMapNumEntries = allocateByteBuffer(4);
      indexMapNumEntries.putInt(0, numImages);
      Future finished = fileChannelWrite(indexMapNumEntries, indexMapFirstEntry_ - 4);
      finished.get();
      try {
         //extra byte of space, just to make sure nothing gets cut off
         raFile_.setLength(filePosition_ + 8);
      } catch (IOException ex) {
         throw new RuntimeException(ex);
      }
   }

   /**
    * Called when entire set of files (i.e. acquisition) is finished. returns a
    * future that returns when its done, if you care
    */
   public Future close() throws IOException, InterruptedException, ExecutionException {
      if (displayStorer_) {
         writeDisplaySettings(masterMPTiffStorage_.getDisplaySettings());
      }

      Future f = executeWritingTask(new Runnable() {
         @Override
         public void run() {
//            reader_.finishedWriting();
            //Dont close file channel and random access file becase Tiff reader still using them
            fileChannel_ = null;
            raFile_ = null;
         }
      });
      return f;
   }

   public boolean hasSpaceToWrite(TaggedImage img) {
      int mdLength = img.tags.toString().length();
      int IFDSize = ENTRIES_PER_IFD * 12 + 4 + 16;
      //5 MB extra padding...just to be safe...
      int extraPadding = 5000000;
      long size = mdLength + IFDSize + bytesPerImagePixels_ + SPACE_FOR_COMMENTS
              + masterMPTiffStorage_.getNumChannels() * DISPLAY_SETTINGS_BYTES_PER_CHANNEL + extraPadding + filePosition_;

      if (size >= MAX_FILE_SIZE) {
         return false;
      }
      return true;
   }

   public boolean isClosed() {
      return raFile_ == null;
   }

   public Future writeImage(TaggedImage img, int t, int c, int z, int p) throws IOException {
      long offset = filePosition_;
      boolean shiftByByte = writeIFD(img);
      Future f = writeBuffers();

      String label = c + "_" + z + "_" + t + "_" + p;

      addToIndexMap(label, offset);
      //Make IFDs start on word
      if (shiftByByte) {
         f = executeWritingTask(new Runnable() {
            @Override
            public void run() {
               try {
                  fileChannel_.position(fileChannel_.position() + 1);
               } catch (IOException ex) {
                  throw new RuntimeException("Couldn't incremement byte");
               }
            }
         });
      }
      return f;
   }

   private void addToIndexMap(String label, long offset) {
      //If a duplicate label is received, forget about the previous one
      //this allows overwriting of images without loss of data
      ByteBuffer buffer = allocateByteBuffer(20);
      String[] indices = label.split("_");
      for (int i = 0; i < 4; i++) {
         buffer.putInt(4 * i, Integer.parseInt(indices[i]));
      }
      buffer.putInt(16, new Long(offset).intValue());
      fileChannelWrite(buffer, indexMapPosition_);
      indexMapPosition_ += 20;
      indexMap_.put(label, offset);
   }

   private Future writeBuffers() throws IOException {
      ByteBuffer[] buffs = new ByteBuffer[buffers_.size()];
      for (int i = 0; i < buffs.length; i++) {
         buffs[i] = buffers_.removeFirst();
      }
      return fileChannelWrite(buffs);
   }

   private long unsignInt(int i) {
      long val = Integer.MAX_VALUE & i;
      if (i < 0) {
         val += (long) Math.pow(2, 31);
      }
      return val;
   }

   public Future overwritePixels(Object pixels, int channel, int slice, int frame, int position) throws IOException {

      long byteOffset = indexMap_.get(channel + "_" + slice + "_" + frame + "_" + position);
      ByteBuffer buffer = ByteBuffer.allocate(2).order(BYTE_ORDER);
      fileChannel_.read(buffer, byteOffset);
      int numEntries = buffer.getChar(0);
      ByteBuffer entries = ByteBuffer.allocate(numEntries * 12 + 4).order(BYTE_ORDER);
      fileChannel_.read(entries, byteOffset + 2);
      long pixelOffset = -1, bytesPerImage = -1;
      //read Tiff tags to find pixel offset
      for (int i = 0; i < numEntries; i++) {
         char tag = entries.getChar(i * 12);
         char type = entries.getChar(i * 12 + 2);
         long count = unsignInt(entries.getInt(i * 12 + 4));
         long value;
         if (type == 3 && count == 1) {
            value = -entries.getChar(i * 12 + 8);
         } else {
            value = unsignInt(entries.getInt(i * 12 + 8));
         }
         if (tag == STRIP_OFFSETS) {
            pixelOffset = value;
         } else if (tag == STRIP_BYTE_COUNTS) {
            bytesPerImage = value;
         }
      }
      if (pixelOffset == -1 || bytesPerImage == -1) {
         throw new RuntimeException("Problem writing downsampled display data for file" + filename_
                 + "\n But full resolution data is unaffected");
      }
      ByteBuffer pixBuff = getPixelBuffer(pixels);
      Future writingDone = fileChannelWrite(pixBuff, pixelOffset);
      return writingDone;
   }

   private boolean writeIFD(TaggedImage img) throws IOException {
      char numEntries = ENTRIES_PER_IFD;
      if (img.tags.has("Summary")) {
         img.tags.remove("Summary");
      }
      byte[] mdBytes = getBytesFromString(img.tags.toString() + " ");

      //2 bytes for number of directory entries, 12 bytes per directory entry, 4 byte offset of next IFD
      //6 bytes for bits per sample if RGB, 16 bytes for x and y resolution, 1 byte per character of MD string
      //number of bytes for pixels
      int totalBytes = 2 + numEntries * 12 + 4 + (rgb_ ? 6 : 0) + 16 + mdBytes.length + bytesPerImagePixels_;
      int IFDandBitDepthBytes = 2 + numEntries * 12 + 4 + (rgb_ ? 6 : 0);

      ByteBuffer ifdBuffer = allocateByteBuffer(IFDandBitDepthBytes);
      CharBuffer charView = ifdBuffer.asCharBuffer();

      long tagDataOffset = filePosition_ + 2 + numEntries * 12 + 4;
      nextIFDOffsetLocation_ = filePosition_ + 2 + numEntries * 12;

      bufferPosition_ = 0;
      charView.put(bufferPosition_, numEntries);
      bufferPosition_ += 2;
      writeIFDEntry(ifdBuffer, charView, WIDTH, (char) 4, 1, imageWidth_);
      writeIFDEntry(ifdBuffer, charView, HEIGHT, (char) 4, 1, imageHeight_);
      writeIFDEntry(ifdBuffer, charView, BITS_PER_SAMPLE, (char) 3, rgb_ ? 3 : 1, rgb_ ? tagDataOffset : byteDepth_ * 8);
      if (rgb_) {
         tagDataOffset += 6;
      }
      writeIFDEntry(ifdBuffer, charView, COMPRESSION, (char) 3, 1, 1);
      writeIFDEntry(ifdBuffer, charView, PHOTOMETRIC_INTERPRETATION, (char) 3, 1, rgb_ ? 2 : 1);

//      if (firstIFD_) {
//         writeIFDEntry(ifdBuffer, charView, IMAGE_DESCRIPTION, (char) 2, 0, 0);
//         ijDescriptionTagPosition_ = filePosition_ + bufferPosition_;
//         writeIFDEntry(ifdBuffer, charView, IMAGE_DESCRIPTION, (char) 2, 0, 0);
//      }
      writeIFDEntry(ifdBuffer, charView, STRIP_OFFSETS, (char) 4, 1, tagDataOffset);
      tagDataOffset += bytesPerImagePixels_;
      writeIFDEntry(ifdBuffer, charView, SAMPLES_PER_PIXEL, (char) 3, 1, (rgb_ ? 3 : 1));
      writeIFDEntry(ifdBuffer, charView, ROWS_PER_STRIP, (char) 3, 1, imageHeight_);
      writeIFDEntry(ifdBuffer, charView, STRIP_BYTE_COUNTS, (char) 4, 1, bytesPerImagePixels_);
      writeIFDEntry(ifdBuffer, charView, X_RESOLUTION, (char) 5, 1, tagDataOffset);
      tagDataOffset += 8;
      writeIFDEntry(ifdBuffer, charView, Y_RESOLUTION, (char) 5, 1, tagDataOffset);
      tagDataOffset += 8;
      writeIFDEntry(ifdBuffer, charView, RESOLUTION_UNIT, (char) 3, 1, 3);

      writeIFDEntry(ifdBuffer, charView, MM_METADATA, (char) 2, mdBytes.length, tagDataOffset);
      tagDataOffset += mdBytes.length;
      //NextIFDOffset
      if (tagDataOffset % 2 == 1) {
         tagDataOffset++; //Make IFD start on word
      }
      ifdBuffer.putInt(bufferPosition_, (int) tagDataOffset);
      bufferPosition_ += 4;

      if (rgb_) {
         charView.put(bufferPosition_ / 2, (char) (byteDepth_ * 8));
         charView.put(bufferPosition_ / 2 + 1, (char) (byteDepth_ * 8));
         charView.put(bufferPosition_ / 2 + 2, (char) (byteDepth_ * 8));
      }
      buffers_.add(ifdBuffer);
      buffers_.add(getPixelBuffer(img.pix));
      buffers_.add(getResolutionValuesBuffer());
      buffers_.add(ByteBuffer.wrap(mdBytes));

      firstIFD_ = false;

      filePosition_ += totalBytes;
      if (filePosition_ % 2 == 1) {
         filePosition_++; //Make IFD start on word
         return true;
      }
      return false;
   }

   private void writeIFDEntry(ByteBuffer buffer, CharBuffer cBuffer, char tag, char type, long count, long value) throws IOException {
      cBuffer.put(bufferPosition_ / 2, tag);
      cBuffer.put(bufferPosition_ / 2 + 1, type);
      buffer.putInt(bufferPosition_ + 4, (int) count);
      if (type == 3 && count == 1) {  //Left justify in 4 byte value field
         cBuffer.put(bufferPosition_ / 2 + 4, (char) value);
         cBuffer.put(bufferPosition_ / 2 + 5, (char) 0);
      } else {
         buffer.putInt(bufferPosition_ + 8, (int) value);
      }
      bufferPosition_ += 12;
   }

   private ByteBuffer getResolutionValuesBuffer() throws IOException {
      ByteBuffer buffer = allocateByteBuffer(16);
      buffer.putInt(0, (int) resNumerator_);
      buffer.putInt(4, (int) resDenomenator_);
      buffer.putInt(8, (int) resNumerator_);
      buffer.putInt(12, (int) resDenomenator_);
      return buffer;
   }

   private ByteBuffer getPixelBuffer(Object pixels) throws IOException {
      try {
         if (rgb_) {
//         if (byteDepth_ == 1) {
            //Original pix in RGBA format, convert to rgb for storage
            byte[] originalPix = (byte[]) pixels;
            byte[] rgbPix = new byte[originalPix.length * 3 / 4];
            int numPix = originalPix.length / 4;
            for (int tripletIndex = 0; tripletIndex < numPix; tripletIndex++) {
               rgbPix[tripletIndex * 3] = originalPix[tripletIndex * 4];
               rgbPix[tripletIndex * 3 + 1] = originalPix[tripletIndex * 4 + 1];
               rgbPix[tripletIndex * 3 + 2] = originalPix[tripletIndex * 4 + 2];
            }
            return ByteBuffer.wrap(rgbPix);
//         } 
//         else {
//            short[] originalPix = (short[]) pixels;
//            short[] rgbaPix = new short[originalPix.length * 3 / 4];
//            int count = 0;
//            for (int i = 0; i < originalPix.length; i++) {
//               if ((i + 1) % 4 != 0) {
//                  //swap R and B for correct format
//                  if ((i + 1) % 4 == 1 ) {
//                     rgbaPix[count] = originalPix[i + 2];
//                  } else if ((i + 1) % 4 == 3) {
//                     rgbaPix[count] = originalPix[i - 2];
//                  } else {                      
//                     rgbaPix[count] = originalPix[i];
//                  }
//                  count++;
//               }
//            }
//            ByteBuffer buffer = allocateByteBufferMemo(rgbaPix.length * 2);
//            buffer.rewind();
//            buffer.asShortBuffer().put(rgbaPix);
//            return buffer;
//         }
         } else if (byteDepth_ == 1) {
            return ByteBuffer.wrap((byte[]) pixels);
         } else {
//            System.out.println("Java version " + getVersion());
            short[] pix = (short[]) pixels;
            Buffer buffer = getLargeBuffer(pix.length * 2);
            buffer.rewind();
            ((ByteBuffer) buffer).asShortBuffer().put(pix);
            return (ByteBuffer) buffer;
         }
      } catch (Exception e) {
         System.err.println(e);
         e.printStackTrace();
         throw new RuntimeException(e);
      }
   }

//   private static int getVersion() {
//      String version = System.getProperty("java.version");
//      if(version.startsWith("1.")) {
//         version = version.substring(2, 3);
//      } else {
//         int dot = version.indexOf(".");
//         if(dot != -1) { version = version.substring(0, dot); }
//      } return Integer.parseInt(version);
//   }

   private void processSummaryMD(JSONObject summaryMD) {
         //Tiff resolution tag values
         double cmPerPixel = 0.0001;
         if (summaryMD.has("PixelSizeUm")) {
            try {
               cmPerPixel = 0.0001 * summaryMD.getDouble("PixelSizeUm");
            } catch (JSONException ex) {
            }
         } else if (summaryMD.has("PixelSize_um")) {
            try {
               cmPerPixel = 0.0001 * summaryMD.getDouble("PixelSize_um");
            } catch (JSONException ex) {
            }
         }
         double log = Math.log10(cmPerPixel);
         if (log >= 0) {
            resDenomenator_ = (long) cmPerPixel;
            resNumerator_ = 1;
         } else {
            resNumerator_ = (long) (1 / cmPerPixel);
            resDenomenator_ = 1;
         }
//      zStepUm_ = AcqEngMetadata.getZStepUm(summaryMD);
   
   }

   private byte[] getBytesFromString(String s) {
      try {
         return s.getBytes("UTF-8");
      } catch (UnsupportedEncodingException ex) {
         throw new RuntimeException("Error encoding String to bytes");
      }
   }

   private void writeNullOffsetAfterLastImage() throws IOException, InterruptedException, ExecutionException {
      ByteBuffer buffer = allocateByteBuffer(4);
      buffer.putInt(0, 0);
      Future finished = fileChannelWrite(buffer, nextIFDOffsetLocation_);
      finished.get();
   }

   private void writeDisplaySettings(JSONObject displaySettings) throws IOException, InterruptedException, ExecutionException {
      ByteBuffer header = allocateByteBuffer(8);
      ByteBuffer buffer = ByteBuffer.wrap(getBytesFromString(displaySettings.toString()));
      int numReservedBytes = buffer.capacity();
      header.putInt(0, DISPLAY_SETTINGS_HEADER);
      header.putInt(4, numReservedBytes);
      fileChannelWrite(header, filePosition_);
      fileChannelWrite(buffer, filePosition_ + 8);

      ByteBuffer offsetHeader = allocateByteBuffer(8);
      offsetHeader.putInt(0, DISPLAY_SETTINGS_OFFSET_HEADER);
      offsetHeader.putInt(4, (int) filePosition_);
      Future done = fileChannelWrite(offsetHeader, 16);
      filePosition_ += numReservedBytes + 8;
      done.get();
   }

   void setDisplayStorer() {
      displayStorer_ = true;
   }

}
