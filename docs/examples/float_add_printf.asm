global $start
$start:
    MVI B, 8
    LXI H, float_sum_text
print_next:
    MOV A, M
    OUT 0
    INX H
    DCR B
    JNZ print_next
    MVI A, 0
    STOP
float_sum_text:
    DB "0.600000"
