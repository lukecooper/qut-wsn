#!/bin/sh

# input
user=$1
address=`avahi-resolve-host-name $2.local -4 | awk '{print $2}'`

# constants
targetdir=qut-wsn
libdir=lib
jarname=qut-wsn-0.1.0-SNAPSHOT.jar
jarfile=target/${jarname}

# ssh scripts
killscript="kill -9 \`ps -A | grep java | awk '{print \$1}'\`"
runscript="pushd ${targetdir}; java -cp ${libdir}/\*:${jarname} qut_wsn.core; popd"

# kill running process
ssh -l $user $address ${killscript}

# copy new jar and config files
lein jar
scp $jarfile $user@$address:$targetdir
scp hosts.list $user@$address:$targetdir
scp network.map $user@$address:$targetdir

# restart
ssh -l $user $address ${runscript} 
