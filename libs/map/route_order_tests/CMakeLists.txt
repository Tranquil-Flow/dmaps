project(route_order_tests)

# Actual production helper via map; no manager/mark implementation substitutes.
omim_add_test(${PROJECT_NAME} route_order_tests.cpp NO_PLATFORM_INIT)
target_link_libraries(${PROJECT_NAME} PRIVATE map)
# Preserve the NaN/Inf and strict-comparison test oracles too.
if (MSVC)
  target_compile_options(${PROJECT_NAME} PRIVATE /fp:strict)
else()
  target_compile_options(${PROJECT_NAME} PRIVATE -fno-fast-math)
endif()
