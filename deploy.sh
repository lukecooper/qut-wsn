#!/bin/sh

user=$1
address=`avahi-resolve-host-name $2.local -4 | awk '{print $2}'`

targetdir=qut-wsn
libdir=$targetdir/lib
jarfile=target/qut-wsn-0.1.0-SNAPSHOT.jar

lein classpath | sed 's/:/\n/g' | grep jar | xargs -iz scp z $user@$address:$libdir
lein jar
scp $jarfile $user@$address:$targetdir
