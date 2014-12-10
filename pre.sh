#!/bin/sh
THIS=`which "$0" 2>/dev/null`
[ $? -gt 0 -a -f "$0" ] && THIS="./$0"
java=java
if test -n "$JAVA_HOME"; then
java="$JAVA_HOME/bin/java"
fi
exec "$java" -jar $THIS "$@"
exit 1 
