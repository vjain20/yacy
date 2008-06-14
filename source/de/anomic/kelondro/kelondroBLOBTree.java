// kelondroBLOBTree.java
// (C) 2004 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 09.02.2004 (as "kelondroDyn.java") on http://yacy.net
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate: 2006-04-02 22:40:07 +0200 (So, 02 Apr 2006) $
// $LastChangedRevision: 1986 $
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

/*
  This class extends the kelondroTree and adds dynamic data handling
  A dynamic content is created, by using several tree nodes and
  combining them over a set of associated keys.
  Example: a byte[] of length 1000 shall be stored in a kelondroTree
  with node size 256. The key for the entry is 'entry'.
  Then kelondroDyn stores the first part of four into the entry
  'entry00', the second into 'entry01', and so on.

*/

package de.anomic.kelondro;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Iterator;

public class kelondroBLOBTree implements kelondroBLOB {

    private static final int counterlen = 8;
    private static final int EcoFSBufferSize = 20;
    
    protected int keylen;
    private int reclen;
    //private int segmentCount;
    private char fillChar;
    private kelondroIndex index;
    private kelondroObjectBuffer buffer;
    private kelondroRow rowdef;
    
    public kelondroBLOBTree(File file, boolean useNodeCache, boolean useObjectCache, int key,
            int nodesize, char fillChar, kelondroByteOrder objectOrder, boolean usetree, boolean writebuffer, boolean resetOnFail) {
        // creates or opens a dynamic tree
        rowdef = new kelondroRow("byte[] key-" + (key + counterlen) + ", byte[] node-" + nodesize, objectOrder, 0);
        kelondroIndex fbi;
        if (usetree) {
			try {
				fbi = new kelondroTree(file, useNodeCache, 0, rowdef, 1, 8);
			} catch (IOException e) {
				e.printStackTrace();
				if (resetOnFail) {
					file.delete();
					try {
						fbi = new kelondroTree(file, useNodeCache, -1, rowdef, 1, 8);
					} catch (IOException e1) {
						e1.printStackTrace();
						throw new kelondroException(e.getMessage());
					}
				} else {
					throw new kelondroException(e.getMessage());
				}
			}
            
        } else {
            if (file.exists()) {
                if (file.isDirectory()) {
                    fbi = new kelondroFlexTable(file.getParentFile(), file.getName(), rowdef, 0, resetOnFail);
                } else {
                    fbi = new kelondroEcoTable(file, rowdef, kelondroEcoTable.tailCacheUsageAuto, EcoFSBufferSize, 0);
                }
            } else {
                fbi = new kelondroEcoTable(file, rowdef, kelondroEcoTable.tailCacheUsageAuto, EcoFSBufferSize, 0);
            }
        }
        this.index = ((useObjectCache) && (!(fbi instanceof kelondroEcoTable))) ? (kelondroIndex) new kelondroCache(fbi) : fbi;
        this.keylen = key;
        this.reclen = nodesize;
        this.fillChar = fillChar;
        //this.segmentCount = 0;
        //if (!(tree.fileExisted)) writeSegmentCount();
        buffer = new kelondroObjectBuffer(file.toString());
    }
    
    public static final void delete(File file) {
        if (file.isFile()) {
            file.delete();
            if (file.exists()) file.deleteOnExit();
        } else {
            kelondroFlexWidthArray.delete(file.getParentFile(), file.getName());
        }
    }
    
    public void clear() throws IOException {
    	String name = this.index.filename();
    	this.index.clear();
    	this.buffer = new kelondroObjectBuffer(name);
    }
    
    public int keylength() {
        return this.rowdef.primaryKeyLength;
    }
    
    public synchronized int size() {
        return index.size();
    }
    
    private static String counter(int c) {
        String s = Integer.toHexString(c);
        while (s.length() < counterlen) s = "0" + s;
        return s;
    }

    private byte[] elementKey(String key, int record) {
        if (key.length() > keylen) throw new RuntimeException("key len (" + key.length() + ") out of limit (" + keylen + "): '" + key + "'");
        while (key.length() < keylen) key = key + fillChar;
        key = key + counter(record);
        return key.getBytes();
    }

    String origKey(byte[] rawKey) {
        int n = keylen - 1;
        if (n >= rawKey.length) n = rawKey.length - 1;
        while ((n > 0) && (rawKey[n] == (byte) fillChar)) n--;
        return new String(rawKey, 0, n + 1);
    }

    public class keyIterator implements kelondroCloneableIterator<String> {
        // the iterator iterates all keys
        kelondroCloneableIterator<kelondroRow.Entry> ri;
        String nextKey;

        public keyIterator(kelondroCloneableIterator<kelondroRow.Entry> iter) {
            ri = iter;
            nextKey = n();
        }

		public keyIterator clone(Object modifier) {
			return new keyIterator(ri.clone(modifier));
		}

        public boolean hasNext() {
            return nextKey != null;
        }

        public String next() {
            String result = nextKey;
            nextKey = n();
            return origKey(result.getBytes());
        }

        public void remove() {
            throw new UnsupportedOperationException("no remove in RawKeyIterator");
        }

        private String n() {
            byte[] g;
            String k;
            String v;
            int c;
            kelondroRow.Entry nt;
            while (ri.hasNext()) {
                nt = ri.next();
                if (nt == null) return null;
                g = nt.getColBytes(0);
                if (g == null) return null;
                k = new String(g, 0, keylen);
                v = new String(g, keylen, counterlen);
                try {
                    c = Integer.parseInt(v, 16);
                } catch (NumberFormatException e) {
                    c = -1;
                }
                if (c == 0) return k;
            }
            return null;
        }
    }

    public synchronized kelondroCloneableIterator<String> keys(boolean up, boolean rotating) throws IOException {
        // iterates only the keys of the Nodes
        // enumerated objects are of type String
        keyIterator i = new keyIterator(index.rows(up, null));
        if (rotating) return new kelondroRotateIterator<String>(i, null, index.size()); else return i;
    }

    public synchronized keyIterator keys(boolean up, byte[] firstKey) throws IOException {
        return new keyIterator(index.rows(up, firstKey));
    }
    
    private byte[] getValueCached(byte[] key) throws IOException {

        // read from buffer
        byte[] buffered = (byte[]) buffer.get(key);
        if (buffered != null) return buffered;
        
        // read from db
        kelondroRow.Entry result = index.get(key);
        if (result == null) return null;

        // return result
        return result.getColBytes(1);
    }

    private synchronized void setValueCached(byte[] key, byte[] value) throws IOException {
        // update storage
        synchronized (this) {
            index.put(rowdef.newEntry(new byte[][]{key, value}));
            buffer.put(key, value);
        }
    }

    synchronized int get(String key, int pos) throws IOException {
        int reccnt = pos / reclen;
        // read within a single record
        byte[] buf = getValueCached(elementKey(key, reccnt));
        if (buf == null) return -1;
        int recpos = pos % reclen;
        if (buf.length <= recpos) return -1;
        return buf[recpos] & 0xFF;
    }
    
    public synchronized byte[] get(String key, int pos, int len) throws IOException {
        int recpos = pos % reclen;
        int reccnt = pos / reclen;
        byte[] segment1;
        // read first within a single record
        if ((recpos == 0) && (reclen == len)) {
            segment1 = getValueCached(elementKey(key, reccnt));
            if (segment1 == null) return null;
        } else {
            byte[] buf = getValueCached(elementKey(key, reccnt));
            if (buf == null) return null;
            if (buf.length < reclen) {
                byte[] buff = new byte[reclen];
                System.arraycopy(buf, 0, buff, 0, buf.length);
                buf = buff;
                buff = null;
            }
            // System.out.println("read:
            // buf.length="+buf.length+",recpos="+recpos+",len="+len);
            if (recpos + len <= reclen) {
                segment1 = new byte[len];
                System.arraycopy(buf, recpos, segment1, 0, len);
            } else {
                segment1 = new byte[reclen - recpos];
                System.arraycopy(buf, recpos, segment1, 0, reclen - recpos);
            }
        }
        // if this is all, return
        if (recpos + len <= reclen) return segment1;
        // read from several records
        // we combine recursively all participating records
        // we have two segments: the one in the starting record, and the remaining
        // segment 1 in record <reccnt> : start = recpos, length = reclen - recpos
        // segment 2 in record <reccnt>+1: start = 0, length = len - reclen + recpos
        // recursively step further
        byte[] segment2 = get(key, pos + segment1.length, len - segment1.length);
        if (segment2 == null) return segment1;
        // now combine the two segments into the result
        byte[] result = new byte[segment1.length + segment2.length];
        System.arraycopy(segment1, 0, result, 0, segment1.length);
        System.arraycopy(segment2, 0, result, segment1.length, segment2.length);
        return result;
    }

    public synchronized void put(String key, int pos, byte[] b, int off, int len) throws IOException {
        int recpos = pos % reclen;
        int reccnt = pos / reclen;
        byte[] buf;
        // first write current record
        if ((recpos == 0) && (reclen == len)) {
            if (off == 0) {
                setValueCached(elementKey(key, reccnt), b);
            } else {
                buf = new byte[len];
                System.arraycopy(b, off, buf, 0, len);
                setValueCached(elementKey(key, reccnt), b);
            }
        } else {
            buf = getValueCached(elementKey(key, reccnt));
            if (buf == null) {
                buf = new byte[reclen];
            } else if (buf.length < reclen) {
                byte[] buff = new byte[reclen];
                System.arraycopy(buf, 0, buff, 0, buf.length);
                buf = buff;
                buff = null;
            }
            // System.out.println("write:
            // b.length="+b.length+",off="+off+",len="+(reclen-recpos));
            if (len < (reclen - recpos))
                System.arraycopy(b, off, buf, recpos, len);
            else
                System.arraycopy(b, off, buf, recpos, reclen - recpos);
            setValueCached(elementKey(key, reccnt), buf);
        }
        // if more records are necessary, write to them also recursively
        if (recpos + len > reclen) {
            put(key, pos + reclen - recpos, b, off + reclen - recpos, len - reclen + recpos);
        }
    }

    public synchronized void remove(String key) throws IOException {
        // remove value in cache and tree
        if (key == null) return;
        int recpos = 0;
        byte[] k;
        while (index.get(k = elementKey(key, recpos)) != null) {
            index.remove(k, false);
            buffer.remove(k);
            recpos++;
        }
        //segmentCount--; writeSegmentCount();
    }

    public synchronized boolean exist(String key) throws IOException {
        return (key != null) && (getValueCached(elementKey(key, 0)) != null);
    }

    public synchronized kelondroRA getRA(String filekey) {
        // this returns always a RARecord, even if no existed bevore
        //return new kelondroBufferedRA(new RARecord(filekey), 512, 0);
        return new RARecord(filekey);
    }

    public class RARecord extends kelondroAbstractRA implements kelondroRA {

        int seekpos = 0;

        String filekey;

        public RARecord(String filekey) {
            this.filekey = filekey;
        }

        public long length() throws IOException {
            return Long.MAX_VALUE;
        }
        
        public long available() throws IOException {
            return Long.MAX_VALUE;
        }
        
        public int read() throws IOException {
            return get(filekey, seekpos++);
        }

        public void write(int i) throws IOException {
            byte[] b = new byte[1];
            b[0] = (byte) i;
            put(filekey, seekpos++, b, 0, 1);
        }

        public int read(byte[] b, int off, int len) throws IOException {
            int l = Math.min(b.length - off, len);
            byte[] buf = get(filekey, seekpos, l);
            if (buf == null) return -1;
            l = Math.min(buf.length, l);
            System.arraycopy(buf, 0, b, off, l);
            seekpos += l;
            return l;
        }

        public void write(byte[] b, int off, int len) throws IOException {
            put(filekey, seekpos, b, off, len);
            seekpos += len;
        }

        public void seek(long pos) throws IOException {
            seekpos = (int) pos;
        }

        public void close() throws IOException {
            // no need to do anything here
        }

    }

    public static void writeFile(kelondroBLOB blob, String key, File f) throws IOException {
        // reads a file from the FS and writes it into the database
        kelondroRA kra = null;
        FileInputStream fis = null;
        try {
            kra = blob.getRA(key);
            byte[] buffer = new byte[1024];
            byte[] result = new byte[(int) f.length()];
            fis = new FileInputStream(f);
            int i;
            int pos = 0;
            while ((i = fis.read(buffer)) > 0) {
                System.arraycopy(buffer, 0, result, pos, i);
                pos += i;
            }
            fis.close();
            kra.writeArray(result);
        } finally {
            if (fis != null)
                try {
                    fis.close();
                } catch (Exception e) {
                }
            if (kra != null)
                try {
                    kra.close();
                } catch (Exception e) {
                }
        }
    }
    
    public static void readFile(kelondroBLOB blob, String key, File f) throws IOException {
        // reads a file from the DB and writes it to the FS
        kelondroRA kra = null;
        FileOutputStream fos = null;
        try {
            kra = blob.getRA(key);
            byte[] result = kra.readArray();
            fos = new FileOutputStream(f);
            fos.write(result);
        } finally {
            if (fos != null)
                try {
                    fos.close();
                } catch (Exception e) {
                }
            if (kra != null)
                try {
                    kra.close();
                } catch (Exception e) {
                }
        }
    }
    
    public synchronized void close() {
        index.close();
    }

    public static void main(String[] args) {
        // test app for DB functions
        // reads/writes files to a database table
        // arguments:
        // {-f2db/-db2f} <db-name> <key> <filename>

        if (args.length == 1) {
            // open a db and list keys
            try {
                kelondroBLOBTree kd = new kelondroBLOBTree(new File(args[0]), true, true, 4 ,100, '_', kelondroNaturalOrder.naturalOrder, false, false, true);
                System.out.println(kd.size() + " elements in DB");
                Iterator<String> i = kd.keys(true, false);
                while (i.hasNext())
                    System.out.println(i.next());
                kd.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (args.length == 4) {
            boolean writeFile = (args[0].equals("-db2f"));
            File db = new File(args[1]);
            String key = args[2];
            File f = new File(args[3]);
            kelondroBLOBTree kd;
            try {
                kd = new kelondroBLOBTree(db, true, true, 80, 200, '_', kelondroNaturalOrder.naturalOrder, false, false, true);
                if (writeFile)
                    readFile(kd, key, f);
                else
                    writeFile(kd, key, f);
            } catch (IOException e) {
                System.out.println("ERROR: " + e.toString());
            }
        }
    }
    
    public static int countElements(kelondroBLOBTree t) {
        int count = 0;
        try {
            Iterator<String> iter = t.keys(true, false);
            while (iter.hasNext()) {count++; if (iter.next() == null) System.out.println("ERROR! null element found");}
            return count;
        } catch (IOException e) {
            return -1;
        }
    }
}