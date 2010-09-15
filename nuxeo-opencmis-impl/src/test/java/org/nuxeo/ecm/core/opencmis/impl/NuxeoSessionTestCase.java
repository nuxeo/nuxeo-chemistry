/*
 * (C) Copyright 2010 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Florent Guillaume
 */
package org.nuxeo.ecm.core.opencmis.impl;

import java.io.ByteArrayInputStream;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.Policy;
import org.apache.chemistry.opencmis.client.api.Property;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.junit.Test;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.opencmis.impl.client.NuxeoSession;
import org.nuxeo.ecm.core.storage.sql.SQLRepositoryTestCase;

/**
 * Tests that hit the high-level Session abstraction.
 */
public abstract class NuxeoSessionTestCase extends SQLRepositoryTestCase {

    public static final String NUXEO_ROOT_TYPE = "Root"; // from Nuxeo

    public static final String NUXEO_ROOT_NAME = ""; // NuxeoPropertyDataName;

    public static final String USERNAME = "test";

    public static final String PASSWORD = "test";

    // stream content with non-ASCII characters
    public static final String STREAM_CONTENT = "Caf\u00e9 Diem\none\0two";

    protected Session session;

    protected String rootFolderId;

    protected boolean isAtomPub;

    protected Map<String, String> repoDetails;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        // deployed for fulltext indexing
        deployBundle("org.nuxeo.ecm.core.convert.api");
        deployBundle("org.nuxeo.ecm.core.convert");
        deployBundle("org.nuxeo.ecm.core.convert.plugins");

        openSession(); // nuxeo

        setUpCmisSession();

        setUpData();

        RepositoryInfo rid = session.getBinding().getRepositoryService().getRepositoryInfo(
                getRepositoryId(), null);
        assertNotNull(rid);
        rootFolderId = rid.getRootFolderId();
        assertNotNull(rootFolderId);

        isAtomPub = this instanceof TestNuxeoSessionAtomPub;
    }

    @Override
    public void tearDown() throws Exception {
        tearDownData();
        tearDownCmisSession();
        super.tearDown();
    }

    /** Sets up the client, fills "session". */
    public abstract void setUpCmisSession() throws Exception;

    /** Tears down the client. */
    public abstract void tearDownCmisSession() throws Exception;

    protected void setUpData() throws Exception {
        repoDetails = Helper.makeNuxeoRepository(super.session);
    }

    protected void tearDownData() {
    }

    protected CoreSession getCoreSession() {
        return super.session;
    }

    protected String getRepositoryId() {
        return super.session.getRepositoryName();
    }

    public void testRoot() {
        Folder root = session.getRootFolder();
        assertNotNull(root);
        assertNotNull(root.getName());
        assertNotNull(root.getId());
        assertNull(root.getFolderParent());
        assertNotNull(root.getType());
        assertEquals(NUXEO_ROOT_TYPE, root.getType().getId());
        assertEquals(rootFolderId, root.getPropertyValue("cmis:objectId"));
        assertEquals(NUXEO_ROOT_TYPE,
                root.getPropertyValue("cmis:objectTypeId"));
        assertEquals(NUXEO_ROOT_NAME, root.getName());
        List<Property<?>> props = root.getProperties();
        assertNotNull(props);
        assertTrue(props.size() > 0);
    }

    public void testDefaultProperties() throws Exception {
        Folder root = session.getRootFolder();
        CmisObject child = root.getChildren().iterator().next();
        assertNotNull(child.getProperty("dc:coverage"));
        assertNull(child.getPropertyValue("dc:coverage"));
    }

    public void testCreateObject() {
        Folder root = session.getRootFolder();
        ContentStream contentStream = null;
        VersioningState versioningState = null;
        List<Policy> policies = null;
        List<Ace> addAces = null;
        List<Ace> removeAces = null;
        OperationContext context = NuxeoSession.DEFAULT_CONTEXT;
        Map<String, Serializable> properties = new HashMap<String, Serializable>();
        properties.put("cmis:objectTypeId", "Note");
        properties.put("cmis:name", "mynote");
        properties.put("note", "bla bla");
        Document doc = root.createDocument(properties, contentStream,
                versioningState, policies, addAces, removeAces, context);
        assertNotNull(doc.getId());
        assertEquals("mynote", doc.getName());
        assertEquals("mynote", doc.getPropertyValue("dc:title"));
        assertEquals("bla bla", doc.getPropertyValue("note"));

        // list children
        ItemIterable<CmisObject> children = root.getChildren();
        assertEquals(3, children.getTotalNumItems());
        CmisObject note = null;
        for (CmisObject child : children) {
            if (child.getName().equals("mynote")) {
                note = child;
            }
        }
        assertNotNull("Missing child", note);
        assertEquals("Note", note.getType().getId());
        assertEquals("bla bla", note.getPropertyValue("note"));
    }

    public void testUpdate() throws Exception {
        Document doc;

        doc = (Document) session.getObjectByPath("/testfolder1/testfile1");
        doc.setProperty("dc:title", "new title");
        doc.updateProperties();

        doc = (Document) session.getObjectByPath("/testfolder1/testfile1");
        assertEquals("new title", doc.getPropertyValue("dc:title"));

        doc.updateProperties(Collections.singletonMap("dc:title", "other title"));
        doc.refresh(); // reload
        assertEquals("other title", doc.getPropertyValue("dc:title"));
    }

    public void testContentStream() throws Exception {
        Document file = (Document) session.getObjectByPath("/testfolder1/testfile1");

        // get stream
        ContentStream cs = file.getContentStream();
        assertNotNull(cs);
        assertEquals("text/plain", cs.getMimeType());
        if (!isAtomPub) {
            // TODO fix AtomPub case where the filename is null
            assertEquals("testfile.txt", cs.getFileName());
        }
        if (!isAtomPub) {
            // TODO fix AtomPub case where the length is unknown (streaming)
            assertEquals(Helper.FILE1_CONTENT.length(), cs.getLength());
        }
        assertEquals(Helper.FILE1_CONTENT, Helper.read(cs.getStream(), "UTF-8"));

        // set stream
        // TODO convenience constructors for ContentStreamImpl
        byte[] streamBytes = STREAM_CONTENT.getBytes("UTF-8");
        ByteArrayInputStream stream = new ByteArrayInputStream(streamBytes);
        cs = new ContentStreamImpl("foo.txt",
                BigInteger.valueOf(streamBytes.length),
                "text/plain; charset=UTF-8", stream);
        file.setContentStream(cs, true);

        // refetch stream
        file = (Document) session.getObject(file);
        cs = file.getContentStream();
        assertNotNull(cs);
        // AtomPub lowercases charset -> TODO proper mime type comparison
        assertEquals("text/plain; charset=UTF-8".toLowerCase(),
                cs.getMimeType().toLowerCase());
        if (!isAtomPub) {
            // TODO fix AtomPub case where the filename is null
            assertEquals("foo.txt", cs.getFileName());
        }
        if (!isAtomPub) {
            // TODO fix AtomPub case where the length is unknown (streaming)
            assertEquals(streamBytes.length, cs.getLength());
        }
        assertEquals(STREAM_CONTENT, Helper.read(cs.getStream(), "UTF-8"));

        // delete
        file.deleteContentStream();
        file.refresh();
        try {
            cs = file.getContentStream();
            fail("Should have no content stream");
        } catch (CmisConstraintException e) {
            // ok
            // TODO check we get CmisConstraintException
        } catch (CmisNotSupportedException e) {
            // TODO AtomPub incorrectly throws this
        }
    }

    public void testDeletedInTrash() throws Exception {
        String file5id = repoDetails.get("file5id");

        try {
            session.getObjectByPath("/testfolder1/testfile5");
            fail("file 5 should be in trash");
        } catch (CmisObjectNotFoundException e) {
            // ok
        }
        try {
            session.getObject(session.createObjectId(file5id));
            fail("file 5 should be in trash");
        } catch (CmisObjectNotFoundException e) {
            // ok
        }

        Folder folder = (Folder) session.getObjectByPath("/testfolder1");
        ItemIterable<CmisObject> children = folder.getChildren();
        assertEquals(3, children.getTotalNumItems());
        for (CmisObject child : children) {
            if (child.getName().equals("title5")) {
                fail("file 5 should be in trash");
            }
        }

        // TODO
        // String query =
        // "SELECT cmis:objectId FROM cmis:document WHERE dc:title = 'title5'";
        // ItemIterable<QueryResult> col = session.query(query, false);
        // assertEquals("file 5 should be in trash", 0, col.getTotalNumItems());

        // cannot delete folder, has children
        try {
            folder.delete(true);
            fail("Should not be able to delete non-empty folder");
        } catch (CmisConstraintException e) {
            // ok
        }

        // test trashed child doesn't block folder delete
        for (CmisObject child : folder.getChildren()) {
            child.delete(true);
        }
        folder.delete(true);
    }

    @Test
    public void testDeleteTree() throws Exception {
        Folder folder = (Folder) session.getObjectByPath("/testfolder1");
        List<String> failed = folder.deleteTree(true, null, true);
        assertTrue(failed == null || failed.isEmpty());

        session.clear();
        try {
            session.getObjectByPath("/testfolder1");
            fail("Folder should be deleted");
        } catch (CmisObjectNotFoundException e) {
            // ok
        }
        try {
            session.getObjectByPath("/testfolder1/testfile1");
            fail("Folder should be deleted");
        } catch (CmisObjectNotFoundException e) {
            // ok
        }

        folder = (Folder) session.getObjectByPath("/testfolder2");
        assertNotNull(folder);
    }

    // XXX TODO copy not implemented (and its signature must change)
    public void TODOtestCopy() throws Exception {
        Document doc = (Document) session.getObjectByPath("/testfolder1/testfile1");
        assertEquals("testfile1_Title", doc.getPropertyValue("dc:title"));
        Property<String> prop = session.getObjectFactory().createProperty(
                doc.getType().getPropertyDefinitions().get("dc:title"),
                "new title");
        Document copy = doc.copy(Collections.<Property<?>> singletonList(prop),
                null, null, null, null);
        assertNotSame(doc.getId(), copy.getId());
        assertEquals("new title", copy.getPropertyValue("dc:title"));
    }

    @Test
    public void testMove() throws Exception {
        Folder folder = (Folder) session.getObjectByPath("/testfolder1");
        Document doc = (Document) session.getObjectByPath("/testfolder2/testfolder3/testfile4");
        String docId = doc.getId();

        // TODO add move(target) convenience method
        doc.move(doc.getParents().get(0), folder);

        assertEquals(docId, doc.getId());
        session.clear();
        try {
            session.getObjectByPath("/testfolder2/testfolder3/testfile4");
            fail("Object should be moved away");
        } catch (CmisObjectNotFoundException e) {
            // ok
        }
        Document doc2 = (Document) session.getObjectByPath("/testfolder1/testfile4");
        assertEquals(docId, doc2.getId());
    }

}
