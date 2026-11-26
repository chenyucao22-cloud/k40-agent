#include "infer.h"
#include <iostream>
int main(int argc, char** argv) {
    if (argc != 3) return 2;
    std::atomic_bool cancel(false);
    try { std::cout << infer(argv[1], argv[2], cancel) << '\n'; }
    catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
