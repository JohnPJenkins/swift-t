
/*
 * rbtree.c
 *
 *  Created on: Oct 26, 2012
 *      Author: wozniak
 */

#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "src/tools.h"

#include "src/rbtree.h"

static void
pop_all(struct rbtree* T)
{
  long k;
  void* v;
  int size = T->size;
  int pops = 0;
  while (true)
  {
    bool b = rbtree_pop(T, &k, &v);
    if (!b) { printf("POPPED NULL\n"); break; }
    printf("popped: %li=%s\n\n", k, (char*) v);
    printf("STABLE:\n");
    rbtree_print(T);
    pops++;
  }
  assert(pops == size);
}

static bool
test_cb(struct rbtree_node* node, void* user_data)
{
  printf("node: %li %s\n", node->key, (char*) node->data);
  return false;
}

int
main()
{
  struct rbtree T;
  rbtree_init(&T);

  // TEST 1:

  rbtree_add(&T, 12, "hello");
  rbtree_add(&T, 8,  "hello");
  rbtree_add(&T, 9,  "hello");
  rbtree_add(&T, 10, "hello");
  rbtree_add(&T, 7,  "hello");
  rbtree_add(&T, 15, "hello");
  rbtree_add(&T, 14, "hello");
  rbtree_add(&T, 13, "hello");
  rbtree_print(&T);

  printf("\nITERATOR...\n");
  rbtree_iterator(&T, test_cb, NULL);

  printf("\nREMOVING...\n");

  void* data;
  rbtree_remove(&T, 12, &data);
  printf("remove ok.\n");
  rbtree_print(&T);

  pop_all(&T);

  printf("\n--\n\n");

 // TEST 2: in-order insertion

  for (long i = 1; i <= 20; i++)
  {
    rbtree_add(&T, i, "hello");
    rbtree_print(&T);
  }

  pop_all(&T);

  // TEST 3: random insertion / in-order deletion

  int n = 20;
  long A[n];
  for (int i = 0; i < n; i++)
    A[i] = i;
  shuffle(A, n);
  for (int i = 0; i < n; i++)
    rbtree_add(&T, A[i], NULL);

  printf("COMPLETE TREE:\n");
  rbtree_print(&T);
  printf("\n");

  pop_all(&T);

  // TEST 4: random insertion / random deletion

  shuffle(A, n);
  for (int i = 0; i < n; i++)
    rbtree_add(&T, A[i], NULL);
  shuffle(A, n);
  for (int i = 0; i < n; i++)
  {
    printf("removing: %li\n", A[i]);
    bool b = rbtree_remove(&T, A[i], NULL);
    assert(b);
  }

  // TEST 5: moves

  int m = 8;
  int moves = 2;
  assert(moves < m/2);

  long B[m];
  for (int i = 0; i < m; i++)
    B[i] = i;

  long tmp[m];
  memcpy(tmp, B, m*sizeof(long));
  shuffle(tmp, m);

  // sources
  long S[moves];
  // dests
  long D[moves];
  for (int i = 0; i < moves; i++)
  {
    S[i] = tmp[i];
    D[i] = tmp[m-i-1];
  }

  printf("B:\n");
  print_longs(B, m);
  printf("\n");
  printf("S:\n");
  print_longs(S, moves);
  printf("\n");
  printf("D:\n");
  print_longs(D, moves);
  printf("\n");

  // add all data
  printf("ADDING...\n");
  for (int i = 0; i < m; i++)
    rbtree_add(&T, B[i], NULL);
  rbtree_print(&T);

  // remove all dests (make space for moves)
  printf("REMOVING DESTS...\n");
  for (int i = 0; i < moves; i++)
    rbtree_remove(&T, D[i], NULL);
  rbtree_print(&T);

  printf("MOVING...\n");
  // do each move
  for (int i = 0; i < moves; i++)
  {
    printf("moving: %li to %li\n", S[i], D[i]);
    rbtree_move(&T, S[i], D[i]);
    printf("move done.\n");
    rbtree_print(&T);
  }

  rbtree_clear(&T);

  printf("SIZE: %i\n", T.size);
  printf("DONE\n");
  return 0;
}
