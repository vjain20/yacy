// CrawlSwitchboard.java
// (C) 2005, 2006 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 2005 on http://www.anomic.de
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate$
// $LastChangedRevision$
// $LastChangedBy$
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

package de.anomic.crawler;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

import net.yacy.cora.document.UTF8;
import net.yacy.cora.services.federated.yacy.CacheStrategy;
import net.yacy.kelondro.blob.MapHeap;
import net.yacy.kelondro.data.word.Word;
import net.yacy.kelondro.logging.Log;
import net.yacy.kelondro.order.NaturalOrder;
import net.yacy.kelondro.util.FileUtils;
import net.yacy.kelondro.util.kelondroException;
import net.yacy.repository.RegexHelper;

public final class CrawlSwitchboard {

    public static final String CRAWL_PROFILE_PROXY                 = "proxy";
    public static final String CRAWL_PROFILE_REMOTE                = "remote";
    public static final String CRAWL_PROFILE_SNIPPET_LOCAL_TEXT    = "snippetLocalText";
    public static final String CRAWL_PROFILE_SNIPPET_GLOBAL_TEXT   = "snippetGlobalText";
    public static final String CRAWL_PROFILE_SNIPPET_LOCAL_MEDIA   = "snippetLocalMedia";
    public static final String CRAWL_PROFILE_SNIPPET_GLOBAL_MEDIA  = "snippetGlobalMedia";
    public static final String CRAWL_PROFILE_SURROGATE             = "surrogates";

    public static final String DBFILE_ACTIVE_CRAWL_PROFILES        = "crawlProfilesActive.heap";
    public static final String DBFILE_PASSIVE_CRAWL_PROFILES       = "crawlProfilesPassive.heap";
    public static final String DBFILE_INVALID_CRAWL_PROFILES       = "crawlProfilesInvalid.heap";

    public static final long CRAWL_PROFILE_PROXY_RECRAWL_CYCLE = 60L * 24L;
    public static final long CRAWL_PROFILE_SNIPPET_LOCAL_TEXT_RECRAWL_CYCLE = 60L * 24L * 30L;
    public static final long CRAWL_PROFILE_SNIPPET_GLOBAL_TEXT_RECRAWL_CYCLE = 60L * 24L * 30L;
    public static final long CRAWL_PROFILE_SNIPPET_LOCAL_MEDIA_RECRAWL_CYCLE = 60L * 24L * 30L;
    public static final long CRAWL_PROFILE_SNIPPET_GLOBAL_MEDIA_RECRAWL_CYCLE = 60L * 24L * 30L;
    public static final long CRAWL_PROFILE_SURROGATE_RECRAWL_CYCLE = 60L * 24L * 30L;

    private final Log       log;
    private Map<byte[], Map<String, String>> profilesActiveCrawls, profilesPassiveCrawls, profilesInvalidCrawls;
    public  CrawlProfile    defaultProxyProfile;
    public  CrawlProfile    defaultRemoteProfile;
    public  CrawlProfile    defaultTextSnippetLocalProfile, defaultTextSnippetGlobalProfile;
    public  CrawlProfile    defaultMediaSnippetLocalProfile, defaultMediaSnippetGlobalProfile;
    public  CrawlProfile    defaultSurrogateProfile;
    private final File      queuesRoot;

    public CrawlSwitchboard(
            final String networkName,
            final Log log,
            final File queuesRoot) {

        log.logInfo("Initializing Word Index for the network '" + networkName + "'.");

        if (networkName == null || networkName.length() == 0) {
            log.logSevere("no network name given - shutting down");
            System.exit(0);
        }
        this.log = log;

        // make crawl profiles database and default profiles
        this.queuesRoot = queuesRoot;
        this.queuesRoot.mkdirs();
        this.log.logConfig("Initializing Crawl Profiles");

        final File profilesInvalidFile = new File(queuesRoot, DBFILE_INVALID_CRAWL_PROFILES);
        this.profilesInvalidCrawls = loadFromDB(profilesInvalidFile);
        
        final File profilesActiveFile = new File(queuesRoot, DBFILE_ACTIVE_CRAWL_PROFILES);
        this.profilesActiveCrawls = loadFromDB(profilesActiveFile);
        for (final byte[] handle : this.profilesActiveCrawls.keySet()) {
            final CrawlProfile p;
            p = new CrawlProfile(this.profilesActiveCrawls.get(handle));
            if (!RegexHelper.isValidRegex(p.get(CrawlProfile.FILTER_MUSTMATCH))) {
                this.removeActive(handle);
                this.putInvalid(handle, p);
                Log.logWarning("CrawlProfiles", "removed Profile " + p.handle() + ": " + p.name()
                        + " from active crawls since " + CrawlProfile.FILTER_MUSTMATCH 
                        + " is no valid regular expression: " + p.get(CrawlProfile.FILTER_MUSTMATCH));
            } else if (!RegexHelper.isValidRegex(p.get(CrawlProfile.FILTER_MUSTNOTMATCH))) {
                this.putInvalid(handle, p);
                this.removeActive(handle);
                Log.logWarning("CrawlProfiles", "removed Profile " + p.handle() + ": " + p.name()
                        + " from active crawls since " + CrawlProfile.FILTER_MUSTNOTMATCH 
                        + " is no valid regular expression: " + p.get(CrawlProfile.FILTER_MUSTNOTMATCH));
            } else {
                Log.logInfo("CrawlProfiles", "loaded Profile " + p.handle() + ": " + p.name());
            }
            
        }
        initActiveCrawlProfiles();
        log.logInfo("Loaded active crawl profiles from file " + profilesActiveFile.getName() + ", " + this.profilesActiveCrawls.size() + " entries");

        final File profilesPassiveFile = new File(queuesRoot, DBFILE_PASSIVE_CRAWL_PROFILES);
        this.profilesPassiveCrawls = loadFromDB(profilesPassiveFile);
        for (final byte[] handle : this.profilesPassiveCrawls.keySet()) {
            final CrawlProfile p = new CrawlProfile(this.profilesPassiveCrawls.get(handle));
            Log.logInfo("CrawlProfiles", "loaded Profile " + p.handle() + ": " + p.name());
        }
        log.logInfo("Loaded passive crawl profiles from file " + profilesPassiveFile.getName() +
                ", " + this.profilesPassiveCrawls.size() + " entries" +
                ", " + profilesPassiveFile.length()/1024);
    }

    public CrawlProfile getActive(final byte[] profileKey) {
        if (profileKey == null) return null;
        final Map<String, String> m = this.profilesActiveCrawls.get(profileKey);
        if (m == null) return null;
        return new CrawlProfile(m);
    }
    
    public CrawlProfile getInvalid(final byte[] profileKey) {
        if (profileKey == null) return null;
        final Map<String, String> m = this.profilesInvalidCrawls.get(profileKey);
        if (m == null) return null;
        return new CrawlProfile(m);
    }

    public CrawlProfile getPassive(final byte[] profileKey) {
        if (profileKey == null) return null;
        final Map<String, String> m = this.profilesPassiveCrawls.get(profileKey);
        if (m == null) return null;
        return new CrawlProfile(m);
    }

    public Set<byte[]> getActive() {
        return this.profilesActiveCrawls.keySet();
    }
    
    public Set<byte[]> getInvalid() {
        return this.profilesInvalidCrawls.keySet();
    }

    public Set<byte[]> getPassive() {
        return this.profilesPassiveCrawls.keySet();
    }

    public void removeActive(final byte[] profileKey) {
        if (profileKey == null) return;
        this.profilesActiveCrawls.remove(profileKey);
    }
    
    public void removeInvalid(final byte[] profileKey) {
        if (profileKey == null) return;
        this.profilesInvalidCrawls.remove(profileKey);
    }

    public void removePassive(final byte[] profileKey) {
        if (profileKey == null) return;
        this.profilesPassiveCrawls.remove(profileKey);
    }

    public void putActive(final byte[] profileKey, final CrawlProfile profile) {
        this.profilesActiveCrawls.put(profileKey, profile);
    }
    
    public void putInvalid(final byte[] profileKey, final CrawlProfile profile) {
        this.profilesInvalidCrawls.put(profileKey, profile);
    }

    public void putPassive(final byte[] profileKey, final CrawlProfile profile) {
        this.profilesPassiveCrawls.put(profileKey, profile);
    }

    public void clear() {
    }

    private void initActiveCrawlProfiles() {
        this.defaultProxyProfile = null;
        this.defaultRemoteProfile = null;
        this.defaultTextSnippetLocalProfile = null;
        this.defaultTextSnippetGlobalProfile = null;
        this.defaultMediaSnippetLocalProfile = null;
        this.defaultMediaSnippetGlobalProfile = null;
        this.defaultSurrogateProfile = null;
        CrawlProfile profile;
        String name;
        try {
            for (final byte[] handle: this.profilesActiveCrawls.keySet()) {
                profile = new CrawlProfile(this.profilesActiveCrawls.get(handle));
                name = profile.name();
                if (name.equals(CRAWL_PROFILE_PROXY)) this.defaultProxyProfile = profile;
                if (name.equals(CRAWL_PROFILE_REMOTE)) this.defaultRemoteProfile = profile;
                if (name.equals(CRAWL_PROFILE_SNIPPET_LOCAL_TEXT)) this.defaultTextSnippetLocalProfile = profile;
                if (name.equals(CRAWL_PROFILE_SNIPPET_GLOBAL_TEXT)) this.defaultTextSnippetGlobalProfile = profile;
                if (name.equals(CRAWL_PROFILE_SNIPPET_LOCAL_MEDIA)) this.defaultMediaSnippetLocalProfile = profile;
                if (name.equals(CRAWL_PROFILE_SNIPPET_GLOBAL_MEDIA)) this.defaultMediaSnippetGlobalProfile = profile;
                if (name.equals(CRAWL_PROFILE_SURROGATE)) this.defaultSurrogateProfile = profile;
            }
        } catch (final Exception e) {
            this.profilesActiveCrawls.clear();
            this.defaultProxyProfile = null;
            this.defaultRemoteProfile = null;
            this.defaultTextSnippetLocalProfile = null;
            this.defaultTextSnippetGlobalProfile = null;
            this.defaultMediaSnippetLocalProfile = null;
            this.defaultMediaSnippetGlobalProfile = null;
            this.defaultSurrogateProfile = null;
        }

        if (this.defaultProxyProfile == null) {
            // generate new default entry for proxy crawling
            this.defaultProxyProfile = new CrawlProfile(
                    "proxy", null, CrawlProfile.MATCH_ALL, CrawlProfile.MATCH_NEVER,
                    0 /*Integer.parseInt(getConfig(PROXY_PREFETCH_DEPTH, "0"))*/,
                    CrawlProfile.getRecrawlDate(CRAWL_PROFILE_PROXY_RECRAWL_CYCLE), -1, false,
                    true /*getConfigBool(PROXY_INDEXING_LOCAL_TEXT, true)*/,
                    true /*getConfigBool(PROXY_INDEXING_LOCAL_MEDIA, true)*/,
                    true,
                    false /*getConfigBool(PROXY_INDEXING_REMOTE, false)*/, true, true, true,
                    CacheStrategy.IFFRESH);
            this.profilesActiveCrawls.put(UTF8.getBytes(this.defaultProxyProfile.handle()), this.defaultProxyProfile);
        }
        if (this.defaultRemoteProfile == null) {
            // generate new default entry for remote crawling
            this.defaultRemoteProfile = new CrawlProfile(CRAWL_PROFILE_REMOTE, null, CrawlProfile.MATCH_ALL, CrawlProfile.MATCH_NEVER, 0,
                    -1, -1, true, true, true, false, false, true, true, false, CacheStrategy.IFFRESH);
            this.profilesActiveCrawls.put(UTF8.getBytes(this.defaultRemoteProfile.handle()), this.defaultRemoteProfile);
        }
        if (this.defaultTextSnippetLocalProfile == null) {
            // generate new default entry for snippet fetch and optional crawling
            this.defaultTextSnippetLocalProfile = new CrawlProfile(CRAWL_PROFILE_SNIPPET_LOCAL_TEXT, null, CrawlProfile.MATCH_ALL, CrawlProfile.MATCH_NEVER, 0,
                    CrawlProfile.getRecrawlDate(CRAWL_PROFILE_SNIPPET_LOCAL_TEXT_RECRAWL_CYCLE), -1, true, false, false, true, false, true, true, false, CacheStrategy.IFEXIST);
            this.profilesActiveCrawls.put(UTF8.getBytes(this.defaultTextSnippetLocalProfile.handle()), this.defaultTextSnippetLocalProfile);
        }
        if (this.defaultTextSnippetGlobalProfile == null) {
            // generate new default entry for snippet fetch and optional crawling
            this.defaultTextSnippetGlobalProfile = new CrawlProfile(CRAWL_PROFILE_SNIPPET_GLOBAL_TEXT, null, CrawlProfile.MATCH_ALL, CrawlProfile.MATCH_NEVER, 0,
                    CrawlProfile.getRecrawlDate(CRAWL_PROFILE_SNIPPET_GLOBAL_TEXT_RECRAWL_CYCLE), -1, true, true, true, true, false, true, true, false, CacheStrategy.IFEXIST);
            this.profilesActiveCrawls.put(UTF8.getBytes(this.defaultTextSnippetGlobalProfile.handle()), this.defaultTextSnippetGlobalProfile);
        }
        this.defaultTextSnippetGlobalProfile.setCacheStrategy(CacheStrategy.IFEXIST);
        if (this.defaultMediaSnippetLocalProfile == null) {
            // generate new default entry for snippet fetch and optional crawling
            this.defaultMediaSnippetLocalProfile = new CrawlProfile(CRAWL_PROFILE_SNIPPET_LOCAL_MEDIA, null, CrawlProfile.MATCH_ALL, CrawlProfile.MATCH_NEVER, 0,
                    CrawlProfile.getRecrawlDate(CRAWL_PROFILE_SNIPPET_LOCAL_MEDIA_RECRAWL_CYCLE), -1, true, false, false, true, false, true, true, false, CacheStrategy.IFEXIST);
            this.profilesActiveCrawls.put(UTF8.getBytes(this.defaultMediaSnippetLocalProfile.handle()), this.defaultMediaSnippetLocalProfile);
        }
        if (this.defaultMediaSnippetGlobalProfile == null) {
            // generate new default entry for snippet fetch and optional crawling
            this.defaultMediaSnippetGlobalProfile = new CrawlProfile(CRAWL_PROFILE_SNIPPET_GLOBAL_MEDIA, null, CrawlProfile.MATCH_ALL, CrawlProfile.MATCH_NEVER, 0,
                    CrawlProfile.getRecrawlDate(CRAWL_PROFILE_SNIPPET_GLOBAL_MEDIA_RECRAWL_CYCLE), -1, true, false, true, true, false, true, true, false, CacheStrategy.IFEXIST);
            this.profilesActiveCrawls.put(UTF8.getBytes(this.defaultMediaSnippetGlobalProfile.handle()), this.defaultMediaSnippetGlobalProfile);
        }
        if (this.defaultSurrogateProfile == null) {
            // generate new default entry for surrogate parsing
            this.defaultSurrogateProfile = new CrawlProfile(CRAWL_PROFILE_SURROGATE, null, CrawlProfile.MATCH_ALL, CrawlProfile.MATCH_NEVER, 0,
                    CrawlProfile.getRecrawlDate(CRAWL_PROFILE_SURROGATE_RECRAWL_CYCLE), -1, true, true, false, false, false, true, true, false, CacheStrategy.NOCACHE);
            this.profilesActiveCrawls.put(UTF8.getBytes(this.defaultSurrogateProfile.handle()), this.defaultSurrogateProfile);
        }
    }

    private void resetProfiles() {
        final File pdb = new File(this.queuesRoot, DBFILE_ACTIVE_CRAWL_PROFILES);
        if (pdb.exists()) FileUtils.deletedelete(pdb);
        try {
            this.profilesActiveCrawls = new MapHeap(pdb, Word.commonHashLength, NaturalOrder.naturalOrder, 1024 * 64, 500, ' ');
        } catch (final IOException e1) {
            Log.logException(e1);
            this.profilesActiveCrawls = null;
        }
        initActiveCrawlProfiles();
    }

    public boolean cleanProfiles() throws InterruptedException {
        CrawlProfile entry;
        boolean hasDoneSomething = false;
        try {
            for (final byte[] handle: this.profilesActiveCrawls.keySet()) {
                // check for interruption
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Shutdown in progress");

                // getting next profile
                entry = new CrawlProfile(this.profilesActiveCrawls.get(handle));
                if (!((entry.name().equals(CRAWL_PROFILE_PROXY))  ||
                      (entry.name().equals(CRAWL_PROFILE_REMOTE)) ||
                      (entry.name().equals(CRAWL_PROFILE_SNIPPET_LOCAL_TEXT))  ||
                      (entry.name().equals(CRAWL_PROFILE_SNIPPET_GLOBAL_TEXT)) ||
                      (entry.name().equals(CRAWL_PROFILE_SNIPPET_LOCAL_MEDIA)) ||
                      (entry.name().equals(CRAWL_PROFILE_SNIPPET_GLOBAL_MEDIA)) ||
                      (entry.name().equals(CRAWL_PROFILE_SURROGATE)))) {
                    final CrawlProfile p = new CrawlProfile(entry);
                    this.profilesPassiveCrawls.put(UTF8.getBytes(p.handle()), p);
                    this.profilesActiveCrawls.remove(handle);
                    hasDoneSomething = true;
                }
            }
        } catch (final kelondroException e) {
            resetProfiles();
            hasDoneSomething = true;
        }
        return hasDoneSomething;
    }


    public void close() {
        ((MapHeap) this.profilesActiveCrawls).close();
        ((MapHeap) this.profilesInvalidCrawls).close();
        ((MapHeap) this.profilesPassiveCrawls).close();
    }
    
    
    /**
     * Loads crawl profiles from a DB file.
     * @param file DB file
     * @return crawl profile data
     */
    private Map<byte[], Map<String, String>> loadFromDB(final File file) {
        Map<byte[], Map<String, String>> ret;
        try {
            ret = new MapHeap(file, Word.commonHashLength, NaturalOrder.naturalOrder, 1024 * 64, 500, ' ');
        } catch (final IOException e) {
            Log.logException(e);Log.logException(e);
            FileUtils.deletedelete(file);
            try {
                ret = new MapHeap(file, Word.commonHashLength, NaturalOrder.naturalOrder, 1024 * 64, 500, ' ');
            } catch (final IOException e1) {
                Log.logException(e1);
                ret = null;
            }
        }
        return ret;
    }

}
