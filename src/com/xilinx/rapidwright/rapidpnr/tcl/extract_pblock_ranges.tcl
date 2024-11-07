set file_path "pblock_ranges.json"

proc dict2json {dict} {
    set json "{"
    foreach key [dict keys $dict] {
        if {[string length $json] > 1} {
            append json ","
        }
        append json "\n    "
        set value [dict get $dict $key]
        append json "\"$key\": \"$value\""
    }

    append json "\n}"
    return $json
}

# extract pblock ranges
set pblocks {}
foreach pblock [get_pblocks *] {
    set pblock_name [get_property NAME $pblock]
    set pblock_range [get_property GRID_RANGES $pblock]
    dict set pblocks $pblock_name $pblock_range
}

set file [open $file_path w]
puts $file [dict2json $pblocks]
close $file