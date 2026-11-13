package com.sonybento.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** Binder handoff to the already authorized local shell; exposes no files or general commands. */
public final class VolumeControllerProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String argument, Bundle extras) {
        if (Binder.getCallingUid() != 2000) throw new SecurityException("Only the authorized shell may connect");
        getContext().enforceCallingPermission("android.permission.DUMP", "Shell permission required");
        if (argument != null || !(method.equals("attach") || method.equals("state"))) {
            throw new IllegalArgumentException("Unknown controller request");
        }
        return VolumeService.controllerForShell(method.equals("attach") ? extras : null);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
