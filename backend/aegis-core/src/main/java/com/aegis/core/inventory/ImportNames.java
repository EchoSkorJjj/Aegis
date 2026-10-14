package com.aegis.core.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The names a package's code is imported by (E11-3), for finding it in what a code scan read: an npm package by its
 * own name; a Python distribution by its import packages, which often differ (PyYAML is {@code yaml}, Pillow is
 * {@code PIL}); a Maven artifact by its Java packages, which follow the group id only roughly (Guava is
 * {@code com.google.common}). A name Aegis knows is {@code certain}: when nothing imports it, the package isn't
 * imported. A name it only guesses — Python's conventional spelling, a Maven group id, a library a framework loads
 * without an import (a logging backend, a JDBC driver) — finds an import when there is one, but its absence proves
 * nothing, so the package is unknown, never shown as unused.
 */
final class ImportNames {

    private ImportNames() {
    }

    /**
     * What to look for: the scan's language ({@code npm}, {@code pypi}, {@code maven}) and the modules — exactly, for
     * npm; that module or one below it, for Python and Java — and whether finding none means the package isn't
     * imported.
     */
    record Names(String language, List<String> modules, boolean certain) {

        Names {
            modules = List.copyOf(modules);
        }
    }

    /** The names for a package of {@code ecosystem}; empty for an ecosystem the scan doesn't read. */
    static Optional<Names> of(String ecosystem, String name) {
        if (ecosystem == null || name == null || name.isBlank()) {
            return Optional.empty();
        }
        return switch (ecosystem.strip().toLowerCase(Locale.ROOT)) {
            case "npm" -> Optional.of(new Names("npm", List.of(name.strip()), true));
            case "pypi", "pip" -> Optional.of(python(name.strip()));
            case "maven" -> java(name.strip());
            default -> Optional.empty();
        };
    }

    private static Names python(String distribution) {
        String normalized = distribution.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
        List<String> known = PYTHON.get(normalized);
        if (known != null) {
            return new Names("pypi", known, true);
        }
        // The convention, and the common prefix it drops (python-jose is imported as jose): a guess.
        List<String> guesses = new ArrayList<>(List.of(normalized.replace('-', '_')));
        if (normalized.startsWith("python-") && normalized.length() > "python-".length()) {
            guesses.add(normalized.substring("python-".length()).replace('-', '_'));
        }
        return new Names("pypi", guesses, false);
    }

    private static Optional<Names> java(String coordinates) {
        int colon = coordinates.indexOf(':');
        if (colon <= 0 || colon == coordinates.length() - 1) {
            return Optional.empty();
        }
        String group = coordinates.substring(0, colon);
        Known known = MAVEN.get(coordinates);
        if (known != null) {
            return Optional.of(new Names("maven", known.packages(), known.certain()));
        }
        Known byGroup = MAVEN_GROUPS.get(group);
        if (byGroup != null) {
            return Optional.of(new Names("maven", byGroup.packages(), false));
        }
        return Optional.of(new Names("maven", List.of(group), false));
    }

    /** A Maven artifact's Java packages, and whether code uses it only by importing them. */
    private record Known(List<String> packages, boolean certain) {
    }

    private static Known imported(String... packages) {
        return new Known(List.of(packages), true);
    }

    /** Used through other APIs or loaded by a framework, so its own packages may never be imported. */
    private static Known loaded(String... packages) {
        return new Known(List.of(packages), false);
    }

    /** Python distributions whose import packages Aegis knows, by PEP 503 name: the many that differ, and common ones. */
    private static final Map<String, List<String>> PYTHON = Map.ofEntries(
            Map.entry("pyyaml", List.of("yaml")),
            Map.entry("beautifulsoup4", List.of("bs4")),
            Map.entry("scikit-learn", List.of("sklearn")),
            Map.entry("pillow", List.of("PIL")),
            Map.entry("python-dateutil", List.of("dateutil")),
            Map.entry("opencv-python", List.of("cv2")),
            Map.entry("opencv-python-headless", List.of("cv2")),
            Map.entry("opencv-contrib-python", List.of("cv2")),
            Map.entry("protobuf", List.of("google.protobuf")),
            Map.entry("attrs", List.of("attr", "attrs")),
            Map.entry("pyjwt", List.of("jwt")),
            Map.entry("python-jose", List.of("jose")),
            Map.entry("pycryptodome", List.of("Crypto")),
            Map.entry("pycryptodomex", List.of("Cryptodome")),
            Map.entry("pymongo", List.of("pymongo", "bson", "gridfs")),
            Map.entry("psycopg2", List.of("psycopg2")),
            Map.entry("psycopg2-binary", List.of("psycopg2")),
            Map.entry("psycopg", List.of("psycopg")),
            Map.entry("mysqlclient", List.of("MySQLdb")),
            Map.entry("pymysql", List.of("pymysql")),
            Map.entry("python-dotenv", List.of("dotenv")),
            Map.entry("pyopenssl", List.of("OpenSSL")),
            Map.entry("setuptools", List.of("setuptools", "pkg_resources")),
            Map.entry("google-cloud-storage", List.of("google.cloud.storage")),
            Map.entry("grpcio", List.of("grpc")),
            Map.entry("typing-extensions", List.of("typing_extensions")),
            Map.entry("djangorestframework", List.of("rest_framework")),
            Map.entry("python-multipart", List.of("multipart", "python_multipart")),
            Map.entry("pyzmq", List.of("zmq")),
            Map.entry("websocket-client", List.of("websocket")),
            Map.entry("pysaml2", List.of("saml2")),
            Map.entry("python-ldap", List.of("ldap")),
            Map.entry("dnspython", List.of("dns")),
            Map.entry("pyserial", List.of("serial")),
            Map.entry("gitpython", List.of("git")),
            Map.entry("sentry-sdk", List.of("sentry_sdk")),
            Map.entry("prometheus-client", List.of("prometheus_client")),
            Map.entry("charset-normalizer", List.of("charset_normalizer")),
            Map.entry("python-socketio", List.of("socketio")),
            Map.entry("msgpack", List.of("msgpack")),
            Map.entry("markupsafe", List.of("markupsafe")),
            Map.entry("jinja2", List.of("jinja2")),
            Map.entry("requests", List.of("requests")),
            Map.entry("urllib3", List.of("urllib3")),
            Map.entry("flask", List.of("flask")),
            Map.entry("django", List.of("django")),
            Map.entry("fastapi", List.of("fastapi")),
            Map.entry("starlette", List.of("starlette")),
            Map.entry("pydantic", List.of("pydantic")),
            Map.entry("sqlalchemy", List.of("sqlalchemy")),
            Map.entry("numpy", List.of("numpy")),
            Map.entry("pandas", List.of("pandas")),
            Map.entry("scipy", List.of("scipy")),
            Map.entry("matplotlib", List.of("matplotlib", "mpl_toolkits")),
            Map.entry("aiohttp", List.of("aiohttp")),
            Map.entry("httpx", List.of("httpx")),
            Map.entry("cryptography", List.of("cryptography")),
            Map.entry("certifi", List.of("certifi")),
            Map.entry("idna", List.of("idna")),
            Map.entry("werkzeug", List.of("werkzeug")),
            Map.entry("itsdangerous", List.of("itsdangerous")),
            Map.entry("click", List.of("click")),
            Map.entry("paramiko", List.of("paramiko")),
            Map.entry("lxml", List.of("lxml")),
            Map.entry("tornado", List.of("tornado")),
            Map.entry("celery", List.of("celery")),
            Map.entry("redis", List.of("redis")),
            Map.entry("boto3", List.of("boto3")),
            Map.entry("botocore", List.of("botocore")),
            Map.entry("rsa", List.of("rsa")),
            Map.entry("ecdsa", List.of("ecdsa")),
            Map.entry("pyasn1", List.of("pyasn1")),
            Map.entry("six", List.of("six")),
            Map.entry("simplejson", List.of("simplejson")),
            Map.entry("ujson", List.of("ujson")),
            Map.entry("orjson", List.of("orjson")),
            Map.entry("pyparsing", List.of("pyparsing")),
            Map.entry("packaging", List.of("packaging")),
            Map.entry("jsonschema", List.of("jsonschema")),
            Map.entry("mako", List.of("mako")),
            Map.entry("markdown", List.of("markdown")),
            Map.entry("bleach", List.of("bleach")),
            Map.entry("pygments", List.of("pygments")),
            Map.entry("tqdm", List.of("tqdm")),
            Map.entry("gunicorn", List.of("gunicorn")),
            Map.entry("uvicorn", List.of("uvicorn")),
            Map.entry("openai", List.of("openai")),
            Map.entry("anthropic", List.of("anthropic")),
            Map.entry("groq", List.of("groq")));

    /** Maven artifacts whose Java packages Aegis knows, by {@code group:artifact}. */
    private static final Map<String, Known> MAVEN = Map.ofEntries(
            Map.entry("com.google.guava:guava", imported("com.google.common", "com.google.thirdparty")),
            Map.entry("com.fasterxml.jackson.core:jackson-databind", imported("com.fasterxml.jackson.databind")),
            Map.entry("com.fasterxml.jackson.core:jackson-core", imported("com.fasterxml.jackson.core")),
            Map.entry("com.fasterxml.jackson.core:jackson-annotations", imported("com.fasterxml.jackson.annotation")),
            Map.entry("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml",
                    loaded("com.fasterxml.jackson.dataformat.yaml")),
            Map.entry("com.fasterxml.jackson.dataformat:jackson-dataformat-xml",
                    loaded("com.fasterxml.jackson.dataformat.xml")),
            Map.entry("org.yaml:snakeyaml", imported("org.yaml.snakeyaml")),
            Map.entry("com.google.code.gson:gson", imported("com.google.gson")),
            Map.entry("com.google.protobuf:protobuf-java", imported("com.google.protobuf")),
            Map.entry("commons-io:commons-io", imported("org.apache.commons.io")),
            Map.entry("commons-codec:commons-codec", imported("org.apache.commons.codec")),
            Map.entry("commons-collections:commons-collections", imported("org.apache.commons.collections")),
            Map.entry("org.apache.commons:commons-collections4", imported("org.apache.commons.collections4")),
            Map.entry("org.apache.commons:commons-lang3", imported("org.apache.commons.lang3")),
            Map.entry("commons-lang:commons-lang", imported("org.apache.commons.lang")),
            Map.entry("org.apache.commons:commons-text", imported("org.apache.commons.text")),
            Map.entry("org.apache.commons:commons-compress", imported("org.apache.commons.compress")),
            Map.entry("commons-fileupload:commons-fileupload", imported("org.apache.commons.fileupload")),
            Map.entry("commons-beanutils:commons-beanutils", imported("org.apache.commons.beanutils")),
            Map.entry("commons-net:commons-net", imported("org.apache.commons.net")),
            Map.entry("org.apache.httpcomponents:httpclient", imported("org.apache.http")),
            Map.entry("org.apache.httpcomponents.client5:httpclient5", imported("org.apache.hc.client5")),
            Map.entry("org.projectlombok:lombok", imported("lombok")),
            Map.entry("junit:junit", imported("org.junit", "junit")),
            Map.entry("com.squareup.okhttp3:okhttp", imported("okhttp3")),
            Map.entry("com.squareup.okio:okio", imported("okio")),
            Map.entry("com.squareup.retrofit2:retrofit", imported("retrofit2")),
            Map.entry("org.jsoup:jsoup", imported("org.jsoup")),
            Map.entry("com.thoughtworks.xstream:xstream", imported("com.thoughtworks.xstream")),
            Map.entry("org.apache.logging.log4j:log4j-api", imported("org.apache.logging.log4j")),
            // The implementation: an application often logs through SLF4J or the API and never imports it.
            Map.entry("org.apache.logging.log4j:log4j-core", loaded("org.apache.logging.log4j")),
            Map.entry("ch.qos.logback:logback-classic", loaded("ch.qos.logback")),
            Map.entry("ch.qos.logback:logback-core", loaded("ch.qos.logback")),
            Map.entry("org.postgresql:postgresql", loaded("org.postgresql")),
            Map.entry("com.mysql:mysql-connector-j", loaded("com.mysql")),
            Map.entry("mysql:mysql-connector-java", loaded("com.mysql")),
            Map.entry("com.h2database:h2", loaded("org.h2")),
            Map.entry("org.apache.tomcat.embed:tomcat-embed-core",
                    loaded("org.apache.catalina", "org.apache.tomcat", "org.apache.coyote")));

    /** Groups whose Java packages don't start with the group id; their artifacts' packages are a guess. */
    private static final Map<String, Known> MAVEN_GROUPS = Map.ofEntries(
            Map.entry("commons-io", loaded("org.apache.commons.io")),
            Map.entry("commons-codec", loaded("org.apache.commons.codec")),
            Map.entry("commons-collections", loaded("org.apache.commons.collections")),
            Map.entry("commons-lang", loaded("org.apache.commons.lang")),
            Map.entry("commons-fileupload", loaded("org.apache.commons.fileupload")),
            Map.entry("commons-beanutils", loaded("org.apache.commons.beanutils")),
            Map.entry("commons-logging", loaded("org.apache.commons.logging")),
            Map.entry("com.squareup.okhttp3", loaded("okhttp3")),
            Map.entry("com.google.guava", loaded("com.google.common")),
            Map.entry("org.apache.httpcomponents", loaded("org.apache.http")),
            Map.entry("org.apache.httpcomponents.client5", loaded("org.apache.hc.client5")),
            Map.entry("org.apache.httpcomponents.core5", loaded("org.apache.hc.core5")),
            Map.entry("javax.servlet", loaded("javax.servlet")),
            Map.entry("jakarta.servlet", loaded("jakarta.servlet")));
}
