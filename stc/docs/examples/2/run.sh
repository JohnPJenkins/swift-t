#!/bin/sh -eu

echo "Stand-alone"
./test-g.sh

echo "SWIG"
swig -module g g.h

echo "SWIG-G"
./test-swig-g.sh

echo "TEST-G-TCL"
export TCLLIBPATH=$PWD
tclsh test-g.tcl

echo "TEST-G-SWIFT"
swift-t -r $PWD test-g-1.swift
