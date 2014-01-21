#!/bin/bash
for f in $(lein classpath | sed 's/:/\n/g' | grep "jar") 
do 
    echo $f 
done
