/**
 *  AbstractScoreMap
 *  Copyright 2011 by Michael Peter Christen, mc@yacy.net, Frankfurt am Main, Germany
 *  First released 28.04.2011 at http://yacy.net
 *
 *  $LastChangedDate: 2011-04-14 00:04:23 +0200 (Do, 14 Apr 2011) $
 *  $LastChangedRevision: 7653 $
 *  $LastChangedBy: orbiter $
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *  
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *  
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package net.yacy.cora.ranking;

import java.util.ArrayList;
import java.util.List;


public abstract class AbstractScoreMap<E> implements ScoreMap<E> {

    /**
     * apply all E/int mappings from an external ScoreMap to this ScoreMap
     */
    public void inc(ScoreMap<E> map) {
        if (map == null) return;
        for (E entry: map) {
            this.inc(entry, map.get(entry));
        }
    }
    
    /**
     * divide the map into two halve parts using the count of the entries
     * @param score
     * @return the objects of the smaller entries from at least 1/2 of the list
     */
    public List<E> lowerHalf() {
        
        // first calculate the average of the entries
        long a = 0;
        for (E entry: this) a += get(entry);
        a = a / this.size();
        
        // then select all entries which are smaller that the average

        ArrayList<E> list = new ArrayList<E>();
        for (E entry: this) if (get(entry) < a) list.add(entry);
        return list;
        
        /*
        int half = this.size() >> 1;
        int smallestCount = 0;
        ArrayList<E> list = new ArrayList<E>();
        while (list.size() < half) {
            for (E entry: this) {
                if (get(entry) == smallestCount) list.add(entry);
            }
            smallestCount++;
        }
        return list;
        */
    }
}
