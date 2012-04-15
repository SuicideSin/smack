/*
 * Copyright 2009 Jonas Ådahl.
 * Copyright 2011 Florian Schmaus			 
 *
 * All rights reserved. Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jivesoftware.smackx.entitycaps;

import org.jivesoftware.smack.Connection;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.filter.AndFilter;
import org.jivesoftware.smack.filter.PacketTypeFilter;
import org.jivesoftware.smack.filter.PacketExtensionFilter;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smack.util.Base64;
import org.jivesoftware.smackx.FormField;
import org.jivesoftware.smackx.ServiceDiscoveryManager;
import org.jivesoftware.smackx.ServiceDiscoveryManagerInterface;
import org.jivesoftware.smackx.provider.CapsExtensionProvider;
import org.jivesoftware.smackx.packet.DiscoverInfo;
import org.jivesoftware.smackx.packet.CapsExtension;
import org.jivesoftware.smackx.packet.DataForm;
import org.jivesoftware.smackx.packet.DiscoverInfo.Feature;

import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;


/**
 * Keeps track of entity capabilities.
 */
public class EntityCapsManager {

    public static final String HASH_METHOD = "sha-1";
    public static final String HASH_METHOD_CAPS = "SHA-1";
    
    // TODO entityNode should become a constant (final)
    private static String entityNode = "http://www.igniterealtime.org/projects/smack/";
    private static EntityCapsPersistentCache persistentCache;

    /**
     * Map of (node, hash algorithm) -&gt; DiscoverInfo. 
     */
    private static Map<String,DiscoverInfo> caps =
        new ConcurrentHashMap<String,DiscoverInfo>();
    
    /**
     * Map of Full JID -&gt; DiscoverInfo/null.
     * In case of c2s connection the key is formed as user@server/resource (resource is required)
     * In case of link-local connection the key is formed as user@host (no resource)
     * In case of a server or component the key is formed as domain
     */
    private Map<String,String> userCaps =
        new ConcurrentHashMap<String,String>(); 

    // CapsVerListeners gets notified when the version string is changed.
    private Set<CapsVerListener> capsVerListeners =
        new CopyOnWriteArraySet<CapsVerListener>();

    private String currentCapsVersion = null;

    static {
        ProviderManager.getInstance().addExtensionProvider(CapsExtension.NODE_NAME,
                CapsExtension.XMLNS, new CapsExtensionProvider());
    }

    /**
     * Add DiscoverInfo to the database.
     *
     * @param node The node name. Could be for example "http://psi-im.org#q07IKJEyjvHSyhy//CH0CxmKi8w=".
     * @param info DiscoverInfo for the specified node.
     */
    public static void addDiscoverInfoByNode(String node, DiscoverInfo info) {
        cleanupDicsoverInfo(info);

        caps.put(node, info);
        
        if (persistentCache != null)
        	persistentCache.addDiscoverInfoByNodePersistent(node, info);
    }
    
    public EntityCapsManager(ServiceDiscoveryManagerInterface sdm) {
        // Add Entity Capabilities (XEP-0115) feature node.
        sdm.addFeature("http://jabber.org/protocol/caps");
    }

    /**
     * Add a record telling what entity caps node a user has. The entity caps
     * node has the format node#ver.
     *
     * @param user the user (Full JID)
     * @param node the entity caps node#ver
     */
    public void addUserCapsNode(String user, String node) {
    	if (user != null && node != null) {
    		userCaps.put(user, node);
    	}
    }

    /**
     * Remove a record telling what entity caps node a user has.
     *
     * @param user the user (Full JID)
     */
    public void removeUserCapsNode(String user) {
        userCaps.remove(user);
    }

    /**
     * Get the Node version (node#ver) of a user.
     *
     * @param user the user (Full JID)
     * @return the node version.
     */
    public String getNodeVersionByUser(String user) {
        return userCaps.get(user);
    }

    /**
     * Get the discover info given a user name. The discover
     * info is returned if the user has a node#ver associated with
     * it and the node#ver has a discover info associated with it.
     *
     * @param user user name (Full JID)
     * @return the discovered info
     */
    public DiscoverInfo getDiscoverInfoByUser(String user) {
        String capsNode = userCaps.get(user);
        if (capsNode == null)
            return null;

        return getDiscoverInfoByNode(capsNode);
    }

    /**
     * Get our own caps version.
     *
     * @return our own caps version
     */
    public String getCapsVersion() {
        return currentCapsVersion;
    }

    /**
     * Get our own entity node.
     *
     * @return our own entity node.
     */
    public String getNode() {
        return entityNode;
    }

    /**
     * Set our own entity node.
     *
     * @param node the new node
     */
    public void setNode(String node) {
        entityNode = node;
    }

    /**
     * Retrieve DiscoverInfo for a specific node.
     *
     * @param node The node name.
     * @return The corresponding DiscoverInfo or null if none is known.
     */
    public static DiscoverInfo getDiscoverInfoByNode(String node) {
        return caps.get(node);
    }

    private static void cleanupDicsoverInfo(DiscoverInfo info) {
        info.setFrom(null);
        info.setTo(null);
        info.setPacketID(null);
    }

    public void addPacketListener(Connection connection) {
        PacketFilter f =
            new AndFilter(
                    new PacketTypeFilter(Presence.class),
                    new PacketExtensionFilter(CapsExtension.NODE_NAME, CapsExtension.XMLNS));
        connection.addPacketListener(new CapsPacketListener(this), f);
    }

    public void addCapsVerListener(CapsVerListener listener) {
        capsVerListeners.add(listener);

        if (currentCapsVersion != null)
            listener.capsVerUpdated(currentCapsVersion);
    }

    public void removeCapsVerListener(CapsVerListener listener) {
        capsVerListeners.remove(listener);
    }

    private void notifyCapsVerListeners() {
        for (CapsVerListener listener : capsVerListeners) {
            listener.capsVerUpdated(currentCapsVersion);
        }
    }

    /**
     * Calculate Entity Caps version string
     * 
     * @param capsString
     * @return
     */
    private static String capsToHash(String capsString) {
        try {
            MessageDigest md = MessageDigest.getInstance(HASH_METHOD_CAPS);
            byte[] digest = md.digest(capsString.getBytes());
            return Base64.encodeBytes(digest);
        }
        catch (NoSuchAlgorithmException nsae) {
            return null;
        }
    }

    private static String formFieldValuesToCaps(Iterator<String> i) {
        String s = "";
        SortedSet<String> fvs = new TreeSet<String>();
        for (; i.hasNext();) {
            fvs.add(i.next());
        }
        for (String fv : fvs) {
            s += fv + "<";
        }
        return s;
    }

    public void calculateEntityCapsVersion(DiscoverInfo discoverInfo,
            String identityType,
            String identityName,
            DataForm extendedInfo) {
        String s = "";

        // Add identity
        // FIXME language
        s += "client/" + identityType + "//" + identityName + "<";

        // Add features
        SortedSet<String> features = new TreeSet<String>();
        for (Iterator<Feature> it = discoverInfo.getFeatures(); it.hasNext();)
        	features.add(it.next().getVar());

        for (String f : features) {
            s += f + "<";
        }

        if (extendedInfo != null) {
            synchronized (extendedInfo) {
                SortedSet<FormField> fs = new TreeSet<FormField>(
                        new Comparator<FormField>() {
                            public int compare(FormField f1, FormField f2) {
                                return f1.getVariable().compareTo(f2.getVariable());
                            }
                        });

                FormField ft = null;

                for (Iterator<FormField> i = extendedInfo.getFields(); i.hasNext();) {
                    FormField f = i.next();
                    if (!f.getVariable().equals("FORM_TYPE")) {
                        fs.add(f);
                    }
                    else {
                        ft = f;
                    }
                }

                // Add FORM_TYPE values
                if (ft != null) {
                    s += formFieldValuesToCaps(ft.getValues());
                }

                // Add the other values
                for (FormField f : fs) {
                    s += f.getVariable() + "<";
                    s += formFieldValuesToCaps(f.getValues());
                }
            }
        }


        setCurrentCapsVersion(discoverInfo, capsToHash(s));
    }

    /**
     * Set our own caps version.
     *
     * @param capsVersion the new caps version
     */
    public void setCurrentCapsVersion(DiscoverInfo discoverInfo, String capsVersion) {
        currentCapsVersion = capsVersion;
        addDiscoverInfoByNode(getNode() + "#" + capsVersion, discoverInfo);
        notifyCapsVerListeners();
    }
    
    public static void setPersistentCache(EntityCapsPersistentCache cache) {
    	if (persistentCache != null)
    		throw new IllegalStateException("Entity Caps Persistent Cache was already set");
    	persistentCache = cache;
    	persistentCache.replay();
    }
}
