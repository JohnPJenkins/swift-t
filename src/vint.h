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
 * vint.h
 *
 *  Created on: Aug 8, 2013
 *      Author: armstrong
 *
 *  Tools for encoding and decoding variable-length integer quantities.
 *
 *  Encoding format:
 *    We encode least significant bytes first (i.e. little-endian)
 *    first byte: 
 *      lower 6 bits: bits of integer
 *      7th bit: sign byte (1 for negative)
 *      8th bit: 1 if more bytes follow
 *    subsequent byte:
 *      lower 7 bites: bits of integer
 *      8th bit: 1 if more bytes follow
 */

#ifndef CUTILS_VINT_H
#define CUTILS_VINT_H

#include <stdbool.h>

#include "c-utils-types.h"

// Maximum size of int64_t encoded in bytes
// One bit overhead per byte
#define VINT_MAX_BYTES (sizeof(int64_t) + (sizeof(int64_t) - 1) / 8 + 1)

/*
  Return encoded length of a vint
 */
static inline int vint_bytes(int64_t val);

/*
  Encode a vint.  Must have at least VINT_MAX_BYTES or vint_bytes(val)
  space in buffer, whichever is less.
  Returns number of bytes written
 */
static inline int vint_encode(int64_t val, void *buffer);

/*
  Decode a vint.  Returns number of bytes read, or negative on an error
 */
static inline int vint_decode(void *buffer, int len, int64_t *val);





#define VINT_MORE_MASK (0x80)
#define VINT_SIGN_MASK (0x40)
#define VINT_6BIT_MASK (0x3f)
#define VINT_7BIT_MASK (0x7f)

static inline int
vint_bytes(int64_t val)
{
  int len = 1;
  if (val < 0)
    val = -val;
  val >>= 6; // Account for sign bit. 
  while (val != 0) {
    val >>= 7;
    len++;
  }
  return len;
}

static inline int
vint_encode(int64_t val, void *buffer)
{
  unsigned char *buffer2 = buffer;
  unsigned char b; // Current byte being encoded
  bool more; // If more bytes are needed
  bool negative = val < 0;
  if (negative)
    val = -val;

  // First byte has 6 bits of number owing to sign bit
  b = val & VINT_6BIT_MASK;
  val >>= 6;

  if (negative)
    b |= VINT_SIGN_MASK;

  more = val != 0;
  if (more)
    b |= VINT_MORE_MASK;

  buffer2[0] = b;
  int pos = 1;
  while (more)
  {
    b = val & VINT_7BIT_MASK;
    val >>= 7;
    more = val != 0;
    if (more)
      b |= VINT_MORE_MASK;
    buffer2[pos++] = b;
  }
  return pos;
}

typedef struct
{
  signed char sign; // 1 for +ive, -1 for -ive
  int64_t accum;
  int shift; // Bits to shift next byte by
} vint_dec;

/*
  Decode first byte of vint.
  Returns -1 on error, 0 if done, 1 if more to decode
 */
static inline int
vint_decode_start(unsigned char b, vint_dec *dec)
{
  dec->sign = ((b & VINT_SIGN_MASK) != 0) ? -1 : 1;
  dec->accum = b & VINT_6BIT_MASK;
  dec->shift = 6;
  return ((b & VINT_MORE_MASK) != 0) ? 1 : 0;
}

static inline int
vint_decode_more(unsigned char b, vint_dec *dec)
{
  int64_t add = (int64_t)(b & VINT_7BIT_MASK);
  dec->accum += (add << dec->shift);
  dec->shift += 7;

  // check for overflow of unsigned part of int64_t
  if (dec->shift > 63)
  {
    return -1;
  }
  return ((b & VINT_MORE_MASK) != 0) ? 1 : 0;
}

static inline int
vint_decode(void *buffer, int len, int64_t *val)
{
  unsigned char *buffer2 = buffer;
  if (len < 1)
    return -1;

  vint_dec dec;
  int dec_rc = vint_decode_start(buffer2[0], &dec);
  int pos = 1; // Byte position
  
  while (dec_rc == 1)
  {
    if (len <= pos)
    {
      // Too long
      return -1;
    }
    dec_rc = vint_decode_more(buffer2[pos++], &dec);
  }
  if (dec_rc == -1)
  {
    // Error encountered
    return -1;
  }

  *val = dec.accum * dec.sign;
  return pos;
}

#endif
