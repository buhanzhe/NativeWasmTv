#pragma once
#include "common.hpp"
Json nativeXml(const std::string& text);
Json nativeDate(const Json& request);
Json nativeDatabase(const std::string& origin,Json request);
