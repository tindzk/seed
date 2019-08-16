#!/bin/sh
version=$1

/usr/bin/git tag -f $version
/usr/bin/git push origin :refs/tags/$version
/usr/bin/git push origin && /usr/bin/git push origin --tags

echo See http://ci.sparse.tech/tindzk/seed for status
