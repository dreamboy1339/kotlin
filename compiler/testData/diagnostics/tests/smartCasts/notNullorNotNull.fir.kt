fun bar(x: Int?): Int {
    if (x != null) return -1
    if (x == null) return -2
    // Should be unreachable
    return 2 + 2
}
