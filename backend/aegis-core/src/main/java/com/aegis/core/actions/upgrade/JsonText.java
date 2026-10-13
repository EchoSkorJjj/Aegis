package com.aegis.core.actions.upgrade;

import java.util.ArrayList;
import java.util.List;

import com.aegis.core.platform.common.ApiExceptions;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where things are in a JSON document's text, so an edit can change one value or add one member and leave every
 * other byte — indentation, key order, line endings — as the author wrote it. Reads strict JSON (what package.json
 * is); anything else is refused as {@code ApiExceptions.Conflict}.
 */
final class JsonText {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private JsonText() {
    }

    /** One member of an object: its key, and where its key and value start and its value ends (exclusive). */
    record Member(String key, int keyStart, int valueStart, int valueEnd) {
    }

    /** An object: where its braces are, and its members in order. */
    record ObjectSpan(int open, int close, List<Member> members) {

        Member member(String key) {
            return members.stream().filter(member -> member.key().equals(key)).findFirst().orElse(null);
        }
    }

    /** The document's top-level object. */
    static ObjectSpan root(String text) {
        int open = skipSpace(text, text.startsWith("﻿") ? 1 : 0);
        return object(text, open);
    }

    /** The object whose {@code {} is at {@code open}. */
    static ObjectSpan object(String text, int open) {
        if (open >= text.length() || text.charAt(open) != '{') {
            throw unreadable();
        }
        List<Member> members = new ArrayList<>();
        int at = skipSpace(text, open + 1);
        if (at < text.length() && text.charAt(at) == '}') {
            return new ObjectSpan(open, at, members);
        }
        while (true) {
            at = skipSpace(text, at);
            if (at >= text.length() || text.charAt(at) != '"') {
                throw unreadable();
            }
            int keyEnd = endOfString(text, at);
            String key = JSON.readValue(text.substring(at, keyEnd + 1), String.class);
            int colon = skipSpace(text, keyEnd + 1);
            if (colon >= text.length() || text.charAt(colon) != ':') {
                throw unreadable();
            }
            int valueStart = skipSpace(text, colon + 1);
            int valueEnd = endOfValue(text, valueStart);
            members.add(new Member(key, at, valueStart, valueEnd));
            at = skipSpace(text, valueEnd);
            if (at < text.length() && text.charAt(at) == ',') {
                at++;
            } else if (at < text.length() && text.charAt(at) == '}') {
                return new ObjectSpan(open, at, members);
            } else {
                throw unreadable();
            }
        }
    }

    /**
     * {@code text} with a member added at the end of {@code object}, indented as its siblings are (or one level in
     * from the object's own line, with {@code unit}, when it has none).
     */
    static String append(String text, ObjectSpan object, String member, String unit) {
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        if (object.members().isEmpty()) {
            String indent = indentOf(text, object.open());
            return text.substring(0, object.open() + 1) + newline + indent + unit + member + newline + indent
                    + text.substring(object.close());
        }
        Member last = object.members().getLast();
        Member first = object.members().getFirst();
        boolean oneLine = text.substring(object.open(), object.close()).indexOf('\n') < 0;
        String separator = oneLine ? ", " : "," + newline + indentOf(text, first.keyStart());
        return text.substring(0, last.valueEnd()) + separator + member + text.substring(last.valueEnd());
    }

    /** {@code value} as a JSON string literal. */
    static String string(String value) {
        return JSON.writeValueAsString(value);
    }

    /** The whitespace a document indents one level by: its first nested member's, else two spaces. */
    static String unit(String text, ObjectSpan root) {
        if (!root.members().isEmpty()) {
            String indent = indentOf(text, root.members().getFirst().keyStart());
            if (!indent.isEmpty()) {
                return indent;
            }
        }
        return "  ";
    }

    /** The whitespace at the start of the line {@code at} is on, up to the first other character. */
    static String indentOf(String text, int at) {
        int lineStart = text.lastIndexOf('\n', at - 1) + 1;
        int end = lineStart;
        while (end < at && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) {
            end++;
        }
        return text.substring(lineStart, end);
    }

    private static int endOfValue(String text, int start) {
        if (start >= text.length()) {
            throw unreadable();
        }
        char first = text.charAt(start);
        if (first == '"') {
            return endOfString(text, start) + 1;
        }
        if (first == '{' || first == '[') {
            int depth = 0;
            for (int at = start; at < text.length(); at++) {
                char c = text.charAt(at);
                if (c == '"') {
                    at = endOfString(text, at);
                } else if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    if (--depth == 0) {
                        return at + 1;
                    }
                }
            }
            throw unreadable();
        }
        int at = start;
        while (at < text.length() && ",}] \t\r\n".indexOf(text.charAt(at)) < 0) {
            at++;
        }
        return at;
    }

    /** The index of the quote closing the string that opens at {@code start}. */
    private static int endOfString(String text, int start) {
        for (int at = start + 1; at < text.length(); at++) {
            char c = text.charAt(at);
            if (c == '\\') {
                at++;
            } else if (c == '"') {
                return at;
            }
        }
        throw unreadable();
    }

    private static int skipSpace(String text, int at) {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
        return at;
    }

    private static ApiExceptions.Conflict unreadable() {
        return new ApiExceptions.Conflict("package.json is not valid JSON");
    }
}
