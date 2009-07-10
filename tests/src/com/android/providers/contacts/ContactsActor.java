/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.contacts;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.provider.BaseColumns;
import android.provider.ContactsContract;
import android.provider.Contacts.Phones;
import android.provider.ContactsContract.Aggregates;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RestrictionExceptions;
import android.test.IsolatedContext;
import android.test.RenamingDelegatingContext;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;
import android.test.mock.MockPackageManager;
import android.util.Log;

import java.util.HashMap;

/**
 * Helper class that encapsulates an "actor" which is owned by a specific
 * package name. It correctly maintains a wrapped {@link Context} and an
 * attached {@link MockContentResolver}. Multiple actors can be used to test
 * security scenarios between multiple packages.
 */
public class ContactsActor {
    private static final String FILENAME_PREFIX = "test.";

    public static final String PACKAGE_GREY = "edu.example.grey";
    public static final String PACKAGE_RED = "net.example.red";
    public static final String PACKAGE_GREEN = "com.example.green";
    public static final String PACKAGE_BLUE = "org.example.blue";

    public Context context;
    public String packageName;
    public MockContentResolver resolver;
    public ContentProvider provider;

    /**
     * Create an "actor" using the given parent {@link Context} and the specific
     * package name. Internally, all {@link Context} method calls are passed to
     * a new instance of {@link RestrictionMockContext}, which stubs out the
     * security infrastructure.
     */
    public ContactsActor(Context overallContext, String packageName,
            Class<? extends ContentProvider> providerClass, String authority) throws Exception {
        context = new RestrictionMockContext(overallContext, packageName);
        this.packageName = packageName;
        resolver = new MockContentResolver();

        RenamingDelegatingContext targetContextWrapper = new RenamingDelegatingContext(context,
                overallContext, FILENAME_PREFIX);
        Context providerContext = new IsolatedContext(resolver, targetContextWrapper);

        provider = providerClass.newInstance();
        provider.attachInfo(providerContext, null);
        resolver.addProvider(authority, provider);
    }

    /**
     * Mock {@link Context} that reports specific well-known values for testing
     * data protection. The creator can override the owner package name, and
     * force the {@link PackageManager} to always return a well-known package
     * list for any call to {@link PackageManager#getPackagesForUid(int)}.
     * <p>
     * For example, the creator could request that the {@link Context} lives in
     * package name "com.example.red", and also cause the {@link PackageManager}
     * to report that no UID contains that package name.
     */
    private static class RestrictionMockContext extends MockContext {
        private final Context mOverallContext;
        private final String mReportedPackageName;
        private final RestrictionMockPackageManager mPackageManager;

        /**
         * Create a {@link Context} under the given package name.
         */
        public RestrictionMockContext(Context overallContext, String reportedPackageName) {
            mOverallContext = overallContext;
            mReportedPackageName = reportedPackageName;
            mPackageManager = new RestrictionMockPackageManager();
            mPackageManager.addPackage(1000, PACKAGE_GREY);
            mPackageManager.addPackage(2000, PACKAGE_RED);
            mPackageManager.addPackage(3000, PACKAGE_GREEN);
            mPackageManager.addPackage(4000, PACKAGE_BLUE);
        }

        @Override
        public String getPackageName() {
            return mReportedPackageName;
        }

        @Override
        public PackageManager getPackageManager() {
            return mPackageManager;
        }

        @Override
        public Resources getResources() {
            return mOverallContext.getResources();
        }
    }

    /**
     * Mock {@link PackageManager} that knows about a specific set of packages
     * to help test security models. Because {@link Binder#getCallingUid()}
     * can't be mocked, you'll have to find your mock-UID manually using your
     * {@link Context#getPackageName()}.
     */
    private static class RestrictionMockPackageManager extends MockPackageManager {
        private final HashMap<Integer, String> mForward = new HashMap<Integer, String>();
        private final HashMap<String, Integer> mReverse = new HashMap<String, Integer>();

        /**
         * Add a UID-to-package mapping, which is then stored internally.
         */
        public void addPackage(int packageUid, String packageName) {
            mForward.put(packageUid, packageName);
            mReverse.put(packageName, packageUid);
        }

        @Override
        public String[] getPackagesForUid(int uid) {
            return new String[] { mForward.get(uid) };
        }

        @Override
        public ApplicationInfo getApplicationInfo(String packageName, int flags) {
            ApplicationInfo info = new ApplicationInfo();
            Integer uid = mReverse.get(packageName);
            info.uid = (uid != null) ? uid : -1;
            return info;
        }
    }

    public long createContact(boolean isRestricted, String name) {
        long contactId = createContact(isRestricted);
        createName(contactId, name);
        return contactId;
    }

    public long createContact(boolean isRestricted) {
        final ContentValues values = new ContentValues();
        values.put(Contacts.PACKAGE, packageName);
        if (isRestricted) {
            values.put(Contacts.IS_RESTRICTED, 1);
        }

        Uri contactUri = resolver.insert(Contacts.CONTENT_URI, values);
        return ContentUris.parseId(contactUri);
    }

    public long createName(long contactId, String name) {
        final ContentValues values = new ContentValues();
        values.put(Data.CONTACT_ID, contactId);
        values.put(Data.IS_PRIMARY, 1);
        values.put(Data.IS_SUPER_PRIMARY, 1);
        values.put(Data.MIMETYPE, CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE);
        values.put(CommonDataKinds.StructuredName.FAMILY_NAME, name);
        Uri insertUri = Uri.withAppendedPath(ContentUris.withAppendedId(Contacts.CONTENT_URI,
                contactId), Contacts.Data.CONTENT_DIRECTORY);
        Uri dataUri = resolver.insert(insertUri, values);
        return ContentUris.parseId(dataUri);
    }

    public long createPhone(long contactId, String phoneNumber) {
        final ContentValues values = new ContentValues();
        values.put(Data.CONTACT_ID, contactId);
        values.put(Data.IS_PRIMARY, 1);
        values.put(Data.IS_SUPER_PRIMARY, 1);
        values.put(Data.MIMETYPE, Phones.CONTENT_ITEM_TYPE);
        values.put(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber);
        Uri insertUri = Uri.withAppendedPath(ContentUris.withAppendedId(Contacts.CONTENT_URI,
                contactId), Contacts.Data.CONTENT_DIRECTORY);
        Uri dataUri = resolver.insert(insertUri, values);
        return ContentUris.parseId(dataUri);
    }

    public void updateException(String packageProvider, String packageClient, boolean allowAccess) {
        final ContentValues values = new ContentValues();
        values.put(RestrictionExceptions.PACKAGE_PROVIDER, packageProvider);
        values.put(RestrictionExceptions.PACKAGE_CLIENT, packageClient);
        values.put(RestrictionExceptions.ALLOW_ACCESS, allowAccess ? 1 : 0);
        resolver.update(RestrictionExceptions.CONTENT_URI, values, null, null);
    }

    public long getAggregateForContact(long contactId) {
        Uri contactUri = ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId);
        final Cursor cursor = resolver.query(contactUri, Projections.PROJ_CONTACTS, null,
                null, null);
        if (!cursor.moveToFirst()) {
            cursor.close();
            throw new RuntimeException("Contact didn't have an aggregate");
        }
        final long aggId = cursor.getLong(Projections.COL_CONTACTS_AGGREGATE);
        cursor.close();
        return aggId;
    }

    public int getDataCountForAggregate(long aggId) {
        Uri contactUri = Uri.withAppendedPath(ContentUris.withAppendedId(Aggregates.CONTENT_URI,
                aggId), Aggregates.Data.CONTENT_DIRECTORY);
        final Cursor cursor = resolver.query(contactUri, Projections.PROJ_ID, null, null,
                null);
        final int count = cursor.getCount();
        cursor.close();
        return count;
    }

    public void setSuperPrimaryPhone(long dataId) {
        final ContentValues values = new ContentValues();
        values.put(Data.IS_PRIMARY, 1);
        values.put(Data.IS_SUPER_PRIMARY, 1);
        Uri updateUri = ContentUris.withAppendedId(Data.CONTENT_URI, dataId);
        resolver.update(updateUri, values, null, null);
    }

    public long getPrimaryPhoneId(long aggId) {
        Uri aggUri = ContentUris.withAppendedId(Aggregates.CONTENT_URI, aggId);
        final Cursor cursor = resolver.query(aggUri, Projections.PROJ_AGGREGATES, null,
                null, null);
        long primaryPhoneId = -1;
        if (cursor.moveToFirst()) {
            primaryPhoneId = cursor.getLong(Projections.COL_AGGREGATES_PRIMARY_PHONE_ID);
        }
        cursor.close();
        return primaryPhoneId;
    }

    public long createGroup(String groupName) {
        final ContentValues values = new ContentValues();
        values.put(ContactsContract.Groups.PACKAGE, packageName);
        values.put(ContactsContract.Groups.TITLE, groupName);
        Uri groupUri = resolver.insert(ContactsContract.Groups.CONTENT_URI, values);
        return ContentUris.parseId(groupUri);
    }

    public long createGroupMembership(long contactId, long groupId) {
        final ContentValues values = new ContentValues();
        values.put(Data.CONTACT_ID, contactId);
        values.put(Data.MIMETYPE, CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE);
        values.put(CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupId);
        Uri insertUri = Uri.withAppendedPath(ContentUris.withAppendedId(Contacts.CONTENT_URI,
                contactId), Contacts.Data.CONTENT_DIRECTORY);
        Uri dataUri = resolver.insert(insertUri, values);
        return ContentUris.parseId(dataUri);
    }

    /**
     * Various internal database projections.
     */
    private interface Projections {
        static final String[] PROJ_ID = new String[] {
                BaseColumns._ID,
        };

        static final int COL_ID = 0;

        static final String[] PROJ_CONTACTS = new String[] {
                Contacts.AGGREGATE_ID
        };

        static final int COL_CONTACTS_AGGREGATE = 0;

        static final String[] PROJ_AGGREGATES = new String[] {
                Aggregates.PRIMARY_PHONE_ID
        };

        static final int COL_AGGREGATES_PRIMARY_PHONE_ID = 0;

    }
}
