#!/bin/sh

# input
user=$1
address=`avahi-resolve-host-name $2.local -4 | awk '{print $2}'`

# ssh scripts
killscript="kill -9 \`ps -A | grep java | awk '{print \$1}'\`"

# kill running process
ssh -l $user $address ${killscript}
