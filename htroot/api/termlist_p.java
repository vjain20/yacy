// rwilist_p
// ------------
// (C) 2011 by Michael Peter Christen; mc@yacy.net
// first published 25.08.2011 on http://yacy.net
//
// $LastChangedDate: 2011-01-03 21:52:54 +0100 (Mo, 03 Jan 2011) $
// $LastChangedRevision: 7420 $
// $LastChangedBy: orbiter $
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;

import net.yacy.cora.document.ASCII;
import net.yacy.cora.protocol.RequestHeader;
import net.yacy.cora.ranking.Rating;
import net.yacy.kelondro.index.Row;
import net.yacy.kelondro.logging.Log;
import net.yacy.search.Switchboard;
import net.yacy.search.index.Segment;
import net.yacy.search.index.Segments;
import de.anomic.server.serverObjects;
import de.anomic.server.serverSwitch;

public class termlist_p {

    public static serverObjects respond(final RequestHeader header, final serverObjects post, final serverSwitch env) {

    	final Log log = new Log("TERMLIST");
        final serverObjects prop = new serverObjects();
        final Switchboard sb = (Switchboard) env;
        Segment segment = null;
        final boolean delete = post != null && post.containsKey("delete");
        final long mincount = post == null ? 10000 : post.getLong("mincount", 10000);
        if (post != null && post.containsKey("segment") && sb.verifyAuthentication(header)) {
            segment = sb.indexSegments.segment(post.get("segment"));
        }
        if (segment == null) segment = sb.indexSegments.segment(Segments.Process.PUBLIC);
        final Iterator<Rating<byte[]>> i = segment.termIndex().referenceCountIterator(null, false);
        Rating<byte[]> e;
        int c = 0, termnumber = 0;
        byte[] termhash, maxterm = null;
        long count, mem, maxcount = 0, totalmemory = 0;
        String hstring;
        final Row referenceRow = segment.termIndex().referenceRow();
        final int rowsize = referenceRow.objectsize;
        final ArrayList<byte[]> deleteterms = new ArrayList<byte[]>();
        long over1000 = 0, over10000 = 0, over100000 = 0, over1000000 = 0, over10000000 = 0, over100000000 = 0;
        while (i.hasNext()) {
            e = i.next();
            termnumber++;
            count = e.getScore();
            if (count >= 1000) over1000++;
            if (count >= 10000) over10000++;
            if (count >= 100000) over100000++;
            if (count >= 1000000) over1000000++;
            if (count >= 10000000) over10000000++;
            if (count >= 100000000) over100000000++;
            if (count > maxcount) {
                maxcount = count;
                maxterm = e.getObject();
            }
            if (count < mincount) continue;
            termhash = e.getObject();
            if (delete) deleteterms.add(termhash);
            hstring = ASCII.String(termhash);
            mem = 20 + count * rowsize;
            prop.put("terms_" + c + "_termhash", hstring);
            prop.put("terms_" + c + "_count", count);
            prop.put("terms_" + c + "_memory", mem);
            //log.logWarning("termhash: " + hstring + " | count: " + count + " | memory: " + mem);
            c++;
            totalmemory += mem;
        }
        if (delete) {
            for (final byte[] t: deleteterms) {
                try {
                    segment.termIndex().delete(t);
                } catch (final IOException e1) {
                	log.logWarning("Error deleting " + ASCII.String(t), e1);
                    e1.printStackTrace();
                }
            }
        }
        prop.put("terms", c);
        prop.put("maxterm", maxterm == null ? "" : ASCII.String(maxterm));
        prop.put("maxcount", maxcount);
        prop.put("maxcountmemory", 20 + maxcount * rowsize);
        prop.put("termnumber", termnumber);
        prop.put("totalmemory", totalmemory);
        prop.put("over1000", over1000);
        prop.put("over10000", over10000);
        prop.put("over100000", over100000);
        prop.put("over1000000", over1000000);
        prop.put("over10000000", over10000000);
        prop.put("over100000000", over100000000);

        log.logWarning("finished termlist_p -> terms: " + c);
        log.logWarning("maxterm: "+ (maxterm == null ? "" : ASCII.String(maxterm)));
        log.logWarning("maxcount:  " + maxcount);
        log.logWarning("termnumber: " + termnumber);
        log.logWarning("totalmemory: " + totalmemory);
        // return rewrite properties
        return prop;
    }

}
