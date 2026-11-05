project(storage_integration_tests)

set(SRC
  download_calc_size_test.cpp
  lightweight_matching_tests.cpp
  storage_3levels_tests.cpp
  storage_downloading_tests.cpp
  storage_group_download_tests.cpp
  storage_http_tests.cpp
  storage_update_tests.cpp
  test_defines.cpp
  test_defines.hpp
)

omim_add_test(${PROJECT_NAME} ${SRC} REQUIRE_QT REQUIRE_SERVER)

target_link_libraries(${PROJECT_NAME}
  PRIVATE
    base
    coding
    geometry
    map
    platform
    platform_tests_support
    storage
)

# Owned loopback only: invoke via run_storage_loopback.py, never the legacy
# REQUIRE_SERVER fixture (it may kill other processes). Existing suite unchanged.
omim_add_test(storage_loopback_tests storage_loopback_tests.cpp REQUIRE_QT)
target_link_libraries(storage_loopback_tests PRIVATE
  base coding geometry platform platform_tests_support storage)

if(APPLE)
  target_link_libraries(storage_loopback_tests PRIVATE "-framework CoreFoundation")
endif()
