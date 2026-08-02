/**
 * Code shared by both backends ({@code mise} and {@code vfox}): {@code model}, {@code service},
 * {@code base}, and {@code util}. Grouped under one {@code _common} parent (leading underscore
 * sorts it away from the backend/UI packages in directory listings) so "does this depend on a
 * specific backend?" is answerable by path alone. {@code tui/} is excluded even though it's
 * also backend-agnostic — it's the app's UI layer, not shared backend plumbing, so it stays a
 * top-level package of its own.
 */
@NullMarked
package com.sampong.tambo._common;

import org.jspecify.annotations.NullMarked;
