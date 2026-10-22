package edu.pitt.isl.mobileclaw.notiftest;

import android.os.Bundle;

/**
 * The privileged half of the shell bridge. Shizuku starts an
 * implementation of this in a process running as the ADB shell user (uid 2000), and
 * the app calls it over Binder.
 *
 * Binder, not a pipe, is the whole reason this shape was chosen over the ADB daemon:
 * arguments arrive already parcelled as a String[], so there is no framing to design,
 * no length prefixes to get wrong, and nothing anywhere that could reassemble a
 * command line. `Shizuku.newProcess` would have been fewer lines but it is private,
 * @Deprecated and slated for removal at API 14 — reachable only by reflecting onto a
 * private static method, which is not a foundation.
 *
 * Results come back as a Bundle rather than a custom Parcelable: three primitives do
 * not earn an .aidl parcelable declaration plus a Parcelable class, and Bundle keeps
 * the mapping to ShellResult in exactly one Kotlin function.
 */
interface IPrivilegedService {

    /** Reserved by the Shizuku server; the implementation must System.exit(). */
    void destroy() = 16777114;

    /**
     * Runs argv directly — no shell, so no globbing, no word splitting, no
     * metacharacters. Rejected here as well as app-side: the privileged end must not
     * accept an operation the app never intended to offer.
     */
    Bundle exec(in String[] argv) = 1;

    /**
     * Writes content to path. Separate from exec so file content never becomes an
     * argv element and never goes near `echo`.
     */
    Bundle writeFile(String path, String content) = 2;

    /** uid/pid of the privileged process, for the liveness probe and onboarding. */
    String identity() = 3;
}
