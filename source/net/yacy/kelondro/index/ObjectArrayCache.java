// ObjectArrayCache.java
// (C) 2006 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 08.06.2006 on http://www.anomic.de
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

package net.yacy.kelondro.index;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;

import net.yacy.kelondro.order.NaturalOrder;


public final class ObjectArrayCache {

	// we use two indexes: one for initialization, and one for data aquired during runtime
	// this has a gread advantage, if the setup-data is large. Then a re-organisation of
	// the run-time data does not need much memory and is done faster.
	// we distinguish two phases: the init phase where data can only be written
	// to index0 with addb, and a runtime-phase where data can only be written
	// to index1 with putb.
	
    private final Row rowdef;
    private final RowSet index0;
    private       RowSet index1;
    //private final kelondroOrder<kelondroRow.Entry> entryOrder;
    
    public ObjectArrayCache(final int payloadSize, final int initSize) {
    	this.rowdef = new Row("Cardinal key-4 {b256}, byte[] payload-" + payloadSize, NaturalOrder.naturalOrder);
    	this.index0 = new RowSet(rowdef, initSize);
    	this.index1 = null;
        //this.entryOrder = new kelondroRow.EntryComparator(rowdef.objectOrder);
    }
    
    public final long memoryNeededForGrow() {
        if (index1 == null) 
            return index0.memoryNeededForGrow();
        return index1.memoryNeededForGrow();
    }
    
    public final Row row() {
        return index0.row();
    }
    
    public final byte[] getb(final int ii) {
        assert ii >= 0 : "i = " + ii;
    	final byte[] key = NaturalOrder.encodeLong(ii, 4);
        if (index0 != null) {
            if (index1 == null) {
                // finish initialization phase
                index0.sort();
                index0.uniq();
                index1 = new RowSet(rowdef, 0); 
            }
            final Row.Entry indexentry = index0.get(key);
            if (indexentry != null) return indexentry.getColBytes(1);
        }
        assert (index1 != null);
        final Row.Entry indexentry = index1.get(key);
        if (indexentry == null) return null;
        return indexentry.getColBytes(1);
    }
    
    public final byte[] putb(final int ii, final byte[] value) {
        assert ii >= 0 : "i = " + ii;
        assert value != null;
        final byte[] key = NaturalOrder.encodeLong(ii, 4);
        if (index0 != null) {
            if (index1 == null) {
                // finish initialization phase
                index0.sort();
                index0.uniq();
                index1 = new RowSet(rowdef, 0); 
            }
            final Row.Entry indexentry = index0.get(key);
            if (indexentry != null) {
                final byte[] oldv = indexentry.getColBytes(1);
                indexentry.setCol(0, key);
                indexentry.setCol(1, value);
                index0.put(indexentry);
                return oldv;
            }
            // else place it in the index1
        }
        // at this point index1 cannot be null
        assert (index1 != null);
        
        final Row.Entry newentry = rowdef.newEntry();
        newentry.setCol(0, ii);
        newentry.setCol(1, value);
        final Row.Entry oldentry = index1.replace(newentry);
        if (oldentry == null) return null;
        return oldentry.getColBytes(1);
    }

    public final void addb(final int ii, final byte[] value) {
    	assert index1 == null; // valid only in init-phase
        assert ii >= 0 : "i = " + ii;
        assert value != null;
        final Row.Entry newentry = index0.row().newEntry();
        newentry.setCol(0, ii);
        newentry.setCol(1, value);
        index0.addUnique(newentry);
    }
    
    public final byte[] removeb(final int ii) {
        assert ii >= 0 : "i = " + ii;
        
        final byte[] key = NaturalOrder.encodeLong(ii, 4);
        if (index0 != null) {
            if (index1 == null) {
                // finish initialization phase
                index0.sort();
                index0.uniq();
                index1 = new RowSet(rowdef, 0); 
            }
            final Row.Entry indexentry = index0.remove(key);
            if (indexentry != null) {
                return indexentry.getColBytes(1);
            }
            // else remove it from the index1
        }
        // at this point index1 cannot be null
        assert (index1 != null);
        if (index1.size() == 0) return null;
        final Row.Entry indexentry = index1.remove(key);
        if (indexentry == null) return null;
        return indexentry.getColBytes(1);
    }
    
    public final byte[] removeoneb() {
        if ((index1 != null) && (index1.size() != 0)) {
            final Row.Entry indexentry = index1.removeOne();
            assert (indexentry != null);
            if (indexentry == null) return null;
            //assert consistencyAnalysis0() : "consistency problem: " + consistencyAnalysis();
            return indexentry.getColBytes(1);
        }
        if ((index0 != null) && (index0.size() != 0)) {
            final Row.Entry indexentry = index0.removeOne();
            assert (indexentry != null);
            if (indexentry == null) return null;
            //assert consistencyAnalysis0() : "consistency problem: " + consistencyAnalysis();
            return indexentry.getColBytes(1);
        }
        return null;
    }
    
    public final int size() {
        if ((index0 != null) && (index1 == null)) {
            //assert consistencyAnalysis0() : "consistency problem: " + consistencyAnalysis();
            return index0.size();
        }
        if ((index0 == null) && (index1 != null)) {
            //assert consistencyAnalysis0() : "consistency problem: " + consistencyAnalysis();
            return index1.size();
        }
        assert ((index0 != null) && (index1 != null));
        //assert consistencyAnalysis0() : "consistency problem: " + consistencyAnalysis();
        return index0.size() + index1.size();
    }
    
    public final Iterator<Row.Entry> rows() {
        if (index0 != null) {
            if (index1 == null) {
                // finish initialization phase
                index0.sort();
                index0.uniq();
                index1 = new RowSet(rowdef, 0);
            }
            return index0.rows();
        } else {
        	assert (index1 != null);
            return index1.rows();
        }
    }
    
    public final void flush() {
        if (index0 != null) {
            index0.sort();
            index0.trim(true);
        }
        if (index1 != null) {
            index1.sort();
            index1.trim(true);
        }
    }
    
    public static void main(final String[] args) {
    	boolean assertEnabled = false;
    	assert  assertEnabled = true;
    	System.out.println((assertEnabled) ? "asserts are enabled" : "enable asserts with 'java -ea'; not enabled yet");
		final long start = System.currentTimeMillis();
		final long randomstart = 0;
		final Random random = new Random(randomstart);
		long r;
		Long R;
		int p, rc = 0;
		final ArrayList<Long> ra = new ArrayList<Long>();
		final HashSet<Long> jcontrol = new HashSet<Long>();
		final ObjectArrayCache kcontrol = new ObjectArrayCache(1, 0);
		for (int i = 0; i < 1000000; i++) {
			r = Math.abs(random.nextLong() % 10000);
			//System.out.println("add " + r);
			jcontrol.add(Long.valueOf(r));
			kcontrol.putb((int) r, "x".getBytes());
			if (random.nextLong() % 5 == 0) ra.add(Long.valueOf(r));
			if ((ra.size() > 0) && (random.nextLong() % 7 == 0)) {
				rc++;
				p = Math.abs(random.nextInt() % ra.size());
				R = ra.get(p);
				//System.out.println("remove " + R.longValue());
				jcontrol.remove(R);
				kcontrol.removeb((int) R.longValue());
				assert kcontrol.removeb((int) R.longValue()) == null;
			}
			assert jcontrol.size() == kcontrol.size();
		}
		System.out.println("removed: " + rc + ", size of jcontrol set: "
				+ jcontrol.size() + ", size of kcontrol set: "
				+ kcontrol.size());
		System.out.println("Time: "
				+ ((System.currentTimeMillis() - start) / 1000) + " seconds");
	}
    
}