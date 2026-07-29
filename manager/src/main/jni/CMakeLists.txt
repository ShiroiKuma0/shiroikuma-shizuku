cmake_minimum_required(VERSION 3.22.1)

project("shizuku")

set(CMAKE_CXX_STANDARD 17)

add_compile_options(-Werror=format -fdata-sections -ffunction-sections -fno-exceptions -fno-rtti -fno-threadsafe-statics)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    message("Builing Release...")

    add_compile_options(-Os -flto -fvisibility=hidden -fvisibility-inlines-hidden)
    add_link_options(-flto -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,--strip-all)
else ()
    message("Builing Debug...")

    add_definitions(-DDEBUG)
endif ()

find_package(boringssl REQUIRED CONFIG)
find_package(cxx REQUIRED CONFIG)

add_executable(libshizuku.so
        starter.cpp misc.cpp selinux.cpp cgroup.cpp)

# FORK: the manager's applicationId reaches the native starter from the APP_ID gradle property
# (passed as -DSHIROIKUMA_PACKAGE_NAME in manager/build.gradle's externalNativeBuild arguments),
# so starter.cpp never hardcodes it and cannot drift when the app id changes. The fallback below
# only applies to a bare cmake invocation outside Gradle.
if (NOT DEFINED SHIROIKUMA_PACKAGE_NAME)
    set(SHIROIKUMA_PACKAGE_NAME "shiroikuma.shizuku")
    message(WARNING "SHIROIKUMA_PACKAGE_NAME not supplied by Gradle; defaulting to ${SHIROIKUMA_PACKAGE_NAME}")
endif ()
message("Native starter manager package: ${SHIROIKUMA_PACKAGE_NAME}")
target_compile_definitions(libshizuku.so PRIVATE SHIROIKUMA_PACKAGE_NAME="${SHIROIKUMA_PACKAGE_NAME}")

target_link_libraries(libshizuku.so log cxx::cxx)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_custom_command(TARGET libshizuku.so POST_BUILD
            COMMAND ${CMAKE_STRIP} --remove-section=.comment "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libshizuku.so")
endif ()

add_library(adb SHARED
        adb_pairing.cpp misc.cpp)

target_link_libraries(adb log boringssl::crypto_static cxx::cxx)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_custom_command(TARGET adb POST_BUILD
            COMMAND ${CMAKE_STRIP} --remove-section=.comment "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libadb.so")
endif ()
