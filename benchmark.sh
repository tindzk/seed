#!/bin/sh
/usr/bin/git tag -f benchmark
/usr/bin/git push origin :refs/tags/benchmark
/usr/bin/git push origin && /usr/bin/git push origin --tags

echo See http://ci.sparse.tech/tindzk/seed for results
