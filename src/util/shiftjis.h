#pragma once

#include <optional>
#include <string>
#include <string_view>

void sjis2ascii(char* bData);
char* sjis2utf8(char* input);

// Encodes UTF-8 text as Shift-JIS. Printable ASCII becomes full-width, as memory card titles are usually written.
// Returns nullopt for invalid UTF-8 or a character Shift-JIS can't represent.
std::optional<std::string> utf82sjis(std::string_view input);
