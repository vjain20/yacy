// AbstractReference.java
// (C) 2009 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 14.04.2009 on http://yacy.net
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate: 2009-04-07 11:34:41 +0200 (Di, 07 Apr 2009) $
// $LastChangedRevision: 5783 $
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

package de.anomic.kelondro.text;

import java.util.ArrayList;

public abstract class AbstractReference implements Reference {

    protected static ArrayList<Integer> a(int i) {
        ArrayList<Integer> l = new ArrayList<Integer>(1);
        l.add(i);
        return l;
    }
    protected static int max(ArrayList<Integer> a) {
        assert a.size() > 0;
        if (a.size() == 1) return a.get(0);
        if (a.size() == 2) return Math.max(a.get(0), a.get(1));
        int r = a.get(0);
        for (int i = 1; i < a.size(); i++) if (a.get(i) > r) r = a.get(i);
        return r;
    }
    protected static int min(ArrayList<Integer> a) {
        assert a.size() > 0;
        if (a.size() == 1) return a.get(0);
        if (a.size() == 2) return Math.min(a.get(0), a.get(1));
        int r = a.get(0);
        for (int i = 1; i < a.size(); i++) if (a.get(i) < r) r = a.get(i);
        return r;
    }
    
    public int maxposition() {
        assert positions() > 0;
        if (positions() == 1) return position(0);
        int p = position(0);
        for (int i = positions() - 1; i > 0; i--) if (position(i) > p) p = position(i);
        return p;
    }
    
    public int minposition() {
        assert positions() > 0;
        if (positions() == 1) return position(0);
        int p = position(0);
        for (int i = positions() - 1; i > 0; i--) if (position(i) < p) p = position(i);
        return p;
    }
    
    public int distance() {
        int d = 0;
        for (int i = 0; i < this.positions() - 1; i++) {
            d += Math.abs(this.position(i) - this.position(i + 1));
        }
        return d;
    }
}