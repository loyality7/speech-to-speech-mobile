package com.s2s.plugin.api;

/**
 * The IPC contract an s2s TOOLS plugin exposes.
 *
 * Named for the s2s platform, not for any one host app: the same plugin
 * works in any app that speaks this contract. A host product name has no
 * place in a protocol identifier — an AIDL interface name is part of the
 * binder descriptor, so renaming it later breaks every shipped plugin.
 *
 * Kept deliberately tiny and string-based: an AIDL interface is a
 * compatibility commitment across two independently-installed APKs, so every
 * added method or changed signature is a breaking change for already-shipped
 * plugins. Passing JSON strings rather than a parcelable object graph means
 * a plugin built against host API 1 keeps working when the host adds fields.
 *
 * The plugin runs in its OWN process under its OWN uid and permissions.
 * Nothing here loads plugin code into the host — that is the whole point of
 * using a bound service instead of a downloaded DEX.
 */
interface IS2SToolPlugin {
    /** Host API version this plugin was built against. The host refuses to bind a plugin newer than itself. */
    int apiVersion();

    /** Tool definitions this plugin provides, as a JSON array: [{"name":..,"description":..,"parameters":{..}}]. Metadata only — must not execute anything. */
    String toolDefinitionsJson();

    /**
     * Runs one tool. [argumentsJson] is a flat JSON object of string values.
     * Returns a JSON object: {"output": "...", "isError": false}.
     * Called on a binder thread — the plugin must not assume the main thread.
     */
    String execute(String toolName, String argumentsJson);
}
