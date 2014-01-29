#!/bin/sh

# input
user=$1
address=`avahi-resolve-host-name $2.local -4 | awk '{print $2}'`

# constants
targetdir=qut-wsn
libdir=$targetdir/lib
jarname=qut-wsn-0.1.0-SNAPSHOT.jar
jarfile=target/${jarname}

# ssh scripts
killscript="kill -9 \`ps -A | grep java | awk '{print \$1}'\`"
runnscript="java -cp ${libdir}/\*:${targetdir}/${jarname} qut_wsn.core"

# kill running process
ssh -l $user $address ${killscript}

# copy new jar
lein jar
scp $jarfile $user@$address:$targetdir

# restart
ssh -l $user $address ${runnscript} &
sleep 5
