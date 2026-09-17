#!/bin/sh

#
# Copyright © 2015-2021 the original authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Standard Gradle wrapper launcher script.
##############################################################################

APP_HOME=$( cd "$( dirname "$0" )" >/dev/null && pwd )
APP_NAME="Gradle"

warn () {
    echo "$*"
} >&2

die () {
    echo
    echo "$*"
    echo
    exit 1
} >&2

MAX_FD=maximum

case "$( uname )" in
  Darwin* ) darwin=true ;;
  MSYS* | MINGW* ) msys=true ;;
esac

JAVACMD="java"
if ! command -v java >/dev/null 2>&1; then
    if [ -n "$JAVA_HOME" ] ; then
        JAVACMD="$JAVA_HOME/bin/java"
    fi
fi

if ! "$JAVACMD" -version >/dev/null 2>&1 ; then
    die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH."
fi

exec "$JAVACMD" -cp "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
