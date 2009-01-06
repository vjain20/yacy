// kelondroBLOBHeapModifier.java
// (C) 2009 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 05.01.2009 on http://yacy.net
//
// $LastChangedDate: 2008-03-14 01:16:04 +0100 (Fr, 14 Mrz 2008) $
// $LastChangedRevision: 4558 $
// $LastChangedBy: orbiter $
//
// LICENSE
// 
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package de.anomic.kelondro;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;

import de.anomic.server.logging.serverLog;

public class kelondroBLOBHeapModifier extends kelondroBLOBHeapReader implements kelondroBLOB {
 
    /*
     * This class adds a remove operation to a BLOBHeapReader
     */

    /**
     * create a heap file: a arbitrary number of BLOBs, indexed by an access key
     * The heap file will be indexed upon initialization.
     * @param heapFile
     * @param keylength
     * @param ordering
     * @throws IOException
     */
    public kelondroBLOBHeapModifier(final File heapFile, final int keylength, final kelondroByteOrder ordering) throws IOException {
        super(heapFile, keylength, ordering);
        mergeFreeEntries();
    }
    
    private void mergeFreeEntries() throws IOException {

        // try to merge free entries
        if (super.free.size() > 1) {
            int merged = 0;
            Map.Entry<Long, Integer> lastFree, nextFree;
            final Iterator<Map.Entry<Long, Integer>> i = this.free.entrySet().iterator();
            lastFree = i.next();
            while (i.hasNext()) {
                nextFree = i.next();
                //System.out.println("*** DEBUG BLOB: free-seek = " + nextFree.seek + ", size = " + nextFree.size);
                // check if they follow directly
                if (lastFree.getKey() + lastFree.getValue() + 4 == nextFree.getKey()) {
                    // merge those records
                    this.file.seek(lastFree.getKey());
                    lastFree.setValue(lastFree.getValue() + nextFree.getValue() + 4); // this updates also the free map
                    this.file.writeInt(lastFree.getValue());
                    this.file.seek(nextFree.getKey());
                    this.file.writeInt(0);
                    i.remove();
                    merged++;
                } else {
                    lastFree = nextFree;
                }
            }
            serverLog.logInfo("kelondroBLOBHeap", "BLOB " + heapFile.getName() + ": merged " + merged + " free records");
        }
    }
    
    /**
     * clears the content of the database
     * @throws IOException
     */
    public synchronized void clear() throws IOException {
        this.index.clear();
        this.free.clear();
        try {
            this.file.close();
        } catch (final IOException e) {
            e.printStackTrace();
        }
        this.heapFile.delete();
        this.file = new kelondroCachedFileRA(heapFile);
    }

    /**
     * close the BLOB table
     */
    public synchronized void close() {
        shrinkWithGapsAtEnd();
        if (file != null) {
            try {
                file.close();
            } catch (final IOException e) {
                e.printStackTrace();
            }
        }
        file = null;
        
        if (index != null && free != null && (index.size() > 3 || free.size() > 3)) {
            // now we can create a dump of the index and the gap information
            // to speed up the next start
            try {
                long start = System.currentTimeMillis();
                free.dump(kelondroBLOBHeapWriter.fingerprintGapFile(this.heapFile));
                free.clear();
                free = null;
                index.dump(kelondroBLOBHeapWriter.fingerprintIndexFile(this.heapFile));
                serverLog.logInfo("kelondroBLOBHeap", "wrote a dump for the " + this.index.size() +  " index entries of " + heapFile.getName()+ " in " + (System.currentTimeMillis() - start) + " milliseconds.");
                index.close();
                index = null;
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            // this is small.. just free resources, do not write index
            free.clear();
            free = null;
            index.close();
            index = null;
        }
    }
    
    /**
     * remove a BLOB
     * @param key  the primary key
     * @throws IOException
     */
    public synchronized void remove(final byte[] key) throws IOException {
        assert index.row().primaryKeyLength == key.length : index.row().primaryKeyLength + "!=" + key.length;
        
        // check if the index contains the key
        final long seek = index.getl(key);
        if (seek < 0) return;
        
        // access the file and read the container
        this.file.seek(seek);
        int size = file.readInt();
        //assert seek + size + 4 <= this.file.length() : heapFile.getName() + ": too long size " + size + " in record at " + seek;
        long filelength = this.file.length(); // put in separate variable for debugging
        if (seek + size + 4 > filelength) {
            serverLog.logSevere("BLOBHeap", heapFile.getName() + ": too long size " + size + " in record at " + seek);
            throw new IOException(heapFile.getName() + ": too long size " + size + " in record at " + seek);
        }
        
        // add entry to free array
        this.free.put(seek, size);
        
        // fill zeros to the content
        int l = size; byte[] fill = new byte[size];
        while (l-- > 0) fill[l] = 0;
        this.file.write(fill, 0, size);
        
        // remove entry from index
        this.index.removel(key);
        
        // recursively merge gaps
        tryMergeNextGaps(seek, size);
        tryMergePreviousGap(seek);
    }
    
    private void tryMergePreviousGap(final long thisSeek) throws IOException {
        // this is called after a record has been removed. That may cause that a new
        // empty record was surrounded by gaps. We merge with a previous gap, if this
        // is also empty, but don't do that recursively
        // If this is successful, it removes the given marker for thisSeed and
        // because of this, this method MUST be called AFTER tryMergeNextGaps was called.
        
        // first find the gap entry for the closest gap in front of the give gap
        SortedMap<Long, Integer> head = this.free.headMap(thisSeek);
        if (head.size() == 0) return;
        long previousSeek = head.lastKey().longValue();
        int previousSize = head.get(previousSeek).intValue();
        
        // check if this is directly in front
        if (previousSeek + previousSize + 4 == thisSeek) {
            // right in front! merge the gaps
            Integer thisSize = this.free.get(thisSeek);
            assert thisSize != null;
            mergeGaps(previousSeek, previousSize, thisSeek, thisSize.intValue());
        }
    }

    private void tryMergeNextGaps(final long thisSeek, final int thisSize) throws IOException {
        // try to merge two gaps if one gap has been processed already and the position of the next record is known
        // if the next record is also a gap, merge these gaps and go on recursively
        
        // first check if next gap position is outside of file size
        long nextSeek = thisSeek + thisSize + 4;
        if (nextSeek >= this.file.length()) return; // end of recursion
        
        // move to next position and read record size
        Integer nextSize = this.free.get(nextSeek);
        if (nextSize == null) return; // finished, this is not a gap
        
        // check if the record is a gap-record
        assert nextSize.intValue() > 0;
        if (nextSize.intValue() == 0) {
            // a strange gap record: we can extend the thisGap with four bytes
            // the nextRecord is a gap record; we remove that from the free list because it will be joined with the current gap
            mergeGaps(thisSeek, thisSize, nextSeek, 0);
                
            // recursively go on
            tryMergeNextGaps(thisSeek, thisSize + 4);
        } else {
            // check if this is a true gap!
            this.file.seek(nextSeek + 4);
            byte[] o = new byte[1];
            this.file.readFully(o, 0, 1);
            int t = o[0];
            assert t == 0;
            if (t == 0) {
                // the nextRecord is a gap record; we remove that from the free list because it will be joined with the current gap
                mergeGaps(thisSeek, thisSize, nextSeek, nextSize.intValue());
                
                // recursively go on
                tryMergeNextGaps(thisSeek, thisSize + 4 + nextSize.intValue());
            }
        }
    }
    
    private void mergeGaps(final long seek0, final int size0, final long seek1, final int size1) throws IOException {
        //System.out.println("*** DEBUG-BLOBHeap " + heapFile.getName() + ": merging gap from pos " + seek0 + ", len " + size0 + " with next record of size " + size1 + " (+ 4)");
        
        Integer g = this.free.remove(seek1); // g is only used for debugging
        assert g != null;
        assert g.intValue() == size1;
        
        // overwrite the size bytes of next records with zeros
        this.file.seek(seek1);
        this.file.writeInt(0);
        
        // the new size of the current gap: old size + len + 4
        int newSize = size0 + 4 + size1;
        this.file.seek(seek0);
        this.file.writeInt(newSize);
        
        // register new gap in the free array; overwrite old gap entry
        g = this.free.put(seek0, newSize);
        assert g != null;
        assert g.intValue() == size0;
    }
    
    protected void shrinkWithGapsAtEnd() {
        // find gaps at the end of the file and shrink the file by these gaps
        try {
            while (this.free.size() > 0) {
                Long seek = this.free.lastKey();
                int size = this.free.get(seek).intValue();
                if (seek.longValue() + size + 4 != this.file.length()) return;
                // shrink the file
                this.file.setLength(seek.longValue());
                this.free.remove(seek);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

	public void put(byte[] key, byte[] b) throws IOException {
		throw new UnsupportedOperationException("put is not supported in BLOBHeapModifier");
	}

}