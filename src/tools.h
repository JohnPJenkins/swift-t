/*
 * Copyright 2013 University of Chicago and Argonne National Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

/*
 * tools.h
 *
 *  Created on: May 4, 2011
 *      Author: wozniak
 */

#ifndef TOOLS_H
#define TOOLS_H


#include <stdbool.h>
#include <assert.h>
#include <inttypes.h>
#include <stdlib.h>

int array_length(void** array);

#define append(string,  args...) \
  string += sprintf(string, ## args)
#define vappend(string, args...) \
  string += vsprintf(string, format, ap)

static inline int
min_integer(int i1, int i2)
{
  if (i1 < i2)
    return i1;
  return i2;
}

static inline int
max_integer(int i1, int i2)
{
  if (i1 > i2)
    return i1;
  return i2;
}

static inline int64_t
min_int64(int64_t i1, int64_t i2)
{
  if (i1 < i2)
    return i1;
  return i2;
}

static inline int64_t
max_int64(int64_t i1, int64_t i2)
{
  if (i1 > i2)
    return i1;
  return i2;
}

static inline uint64_t
min_uint64(uint64_t i1, uint64_t i2)
{
  if (i1 < i2)
    return i1;
  return i2;
}

static inline uint64_t
max_uint64(uint64_t i1, uint64_t i2)
{
  if (i1 > i2)
    return i1;
  return i2;
}


#define bool2string(b) (b ? "true" : "false" )

/**
   Random integer in set [low, high)
 */
static inline int
random_between(int low, int high)
{
  return (low + rand() / ((RAND_MAX / (high - low)) + 1));
}

/**
   Random float in set [low, high)
 */
static inline double
random_between_double(double low, double high)
{
  double r = (double) random();
  double p = r / (double) RAND_MAX;
  double d = low + p * (high - low);
  return d;
}

static inline bool
random_bool(void)
{
  return (bool) random_between(0,2);
}

/**
   input: probability weights for each index - must sum to 1
   output: random index
 */
static inline int
random_draw(float* p, int length)
{
  double weight = 0;
  double r = (double) random();
  double max = (double) RAND_MAX;
  double target = r/max;
  int ix = -1;
  do
  {
    ix++;
    weight += p[ix];
  } while (weight < target && ix < length - 1);
  return ix;
}

/** Called when the check_msg() condition fails */
void check_msg_impl(const char* format, ...);

/** Nice vargs error check and message */
#define check_msg(condition, format, args...)  \
    { if (!(condition))                          \
       check_msg_impl(format, ## args);        \
    }

/**
   Substitute for assert(): handles unused variables under NDEBUG
 */
#define ASSERT(condition) { assert(condition); (void)(condition); }

/** Called when the valgrind_assert() condition fails */
void valgrind_assert_failed(const char *file, int line);

/** Called when the valgrind_assert_msg() condition fails */
void valgrind_assert_failed_msg(const char *file, int line,
                           const char* format, ...);

/**
   VALGRIND_ASSERT
   Substitute for assert(): provide stack trace via valgrind
   If not running under valgrind, works like assert()
 */
#ifdef NDEBUG
#define valgrind_assert(condition)            (void) (condition);
#define valgrind_assert_msg(condition,msg...) (void) (condition);
#else
#define valgrind_assert(condition) \
    if (!(condition)) \
    { valgrind_assert_failed(__FILE__, __LINE__); }
#define valgrind_assert_msg(condition,msg...) \
    if (!(condition)) \
    { valgrind_assert_failed_msg(__FILE__, __LINE__, ## msg); }
#endif

/**
   Cause valgrind assertion error behavior w/o condition
 */
#define valgrind_fail(msg...) \
  valgrind_assert_failed_msg(__FILE__, __LINE__, ## msg)

/**
   Allows for GDB/Eclipse debugging of MPI applications
   From shell, set environment:
     GDB_SPIN=<rank to which you want to attach>
   Then have each process call gdb_spin(rank) with its rank
   gdb_spin() will report the PID to which to attach
   Attach to that PID, then set variable t=1 to continue stepping
 */
void gdb_spin(int target);

/**
   Get time since the Unix Epoch in microseconds
 */
double time_micros(void);

/**
   Sleepfor the given number of seconds (using nanosleep)
 */
void time_delay(double delay);

/**
   Convert environment variable value to integer
   If not found, return default value
   @param dflt The default value
   @return True, false if string could not be converted to integer
 */
bool getenv_integer(const char* name, int dflt, int* result);

/**
   Convert environment variable value to integer
   If not found, return default value
   @param dflt The default value
   @return True, false if string could not be converted to integer
 */
bool getenv_ulong(const char* name, unsigned long dflt,
                  unsigned long* result);

/**
   Receive a true/false setting by env var, which is
   false if "0", or false (case-insensitive),
   and true for a non-zero number or true (case-insensitive)
   If not found, return default value
   @return True, false if string could not be converted to boolean
 */
bool xlb_env_boolean(const char *env_var, bool dflt, bool *result);

/**
   Shuffle array A in-place
 */
void shuffle(long* A, int count);

/**
   Simply print comma-separated array of longs
 */
void print_longs(long* A, int count);

/**
   Read a whole file into a newly allocated string
 */
char* slurp(const char* filename);

#endif
