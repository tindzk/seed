#!/bin/sh
set -e
set -x

version=$1

/usr/bin/git tag -f $version
/usr/bin/git push public :refs/tags/$version
/usr/bin/git push public && /usr/bin/git push public --tags

echo See http://ci.sparse.tech/tindzk/seed for status
