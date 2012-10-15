
To build:

Type

./setup.sh
./configure
make

Creates a library in lib/

To use:

#include the headers from src/ that you want to use.
link with -L woztools/lib -l woztools

Configure options:

export CFLAGS="-O0 -g"
for debugging

export CFLAGS="-D VALGRIND"
to use valgrind (cf. lookup3.c)

Usage: See About.txt
