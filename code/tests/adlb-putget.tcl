
# Flex Turbine+ADLB with quick put/get
# Nice to have for quick manual experiments

# usage: mpiexec -l -n 3 bin/turbine test/adlb-putget.tcl

package require turbine 0.0.1

enum WORK_TYPE { T }

if [ info exists env(ADLB_SERVERS) ] {
    set servers $env(ADLB_SERVERS)
} else {
    set servers ""
}
if { [ string length $servers ] == 0 } {
    set servers 1
}
adlb::init $servers [ array size WORK_TYPE ]

set amserver [ adlb::amserver ]

if { $amserver == 0 } {

    set rank [ adlb::rank ]
    if { $rank == 0 } {
        puts "clock: [ clock seconds ]"
        adlb::put $adlb::RANK_ANY $WORK_TYPE(T) "hello0" 0
        puts "clock: [ clock seconds ]"
    }
    while 1 {
        puts "clock: [ clock seconds ]"
        set msg [ adlb::get $WORK_TYPE(T) answer_rank ]
        puts "msg: '$msg'"
        if { [ string length $msg ] == 0 } break
    }
} else {
    adlb::server
}

puts "finalizing..."
adlb::finalize
puts OK
