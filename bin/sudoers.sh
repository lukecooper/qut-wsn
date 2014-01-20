#!/bin/bash

# args
user=$1
sudo_password=$2

# file locations
tmp_file="sudoers.tmp"
sudo_file="/etc/sudoers"

# ensure temp file doesn't exist
rm -f $tmp_file

# only add user if they don't exist
if echo $sudo_password | sudo -S cat $sudo_file | grep -q $user 
then
  echo "user $user already exists in sudoers"
else
  echo "adding user $user to passwordless sudoers"
  echo "$1 ALL=(ALL) NOPASSWD: ALL" > $tmp_file
  echo $2 | sudo -S tee -a $sudo_file < $tmp_file
fi

# clean up
rm -f $tmp_file

exit 0
