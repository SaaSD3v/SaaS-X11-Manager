/* SPDX-License-Identifier: GPL-3.0-or-later
 * Audio regression fixture. Includes the pinned upstream daemon translation
 * unit so recv_req() and its limits are tested directly, without copying or
 * reimplementing the parser. Unused daemon functions are removed by the linker.
 * Namespace entry is replaced with execvp; this fixture never starts a daemon,
 * changes namespaces, or reads/writes container configuration on its own.
 */
#include "daemon.c"

int main(int argc, char **argv) {
  FILE *wire = tmpfile();
  if (!wire)
    return 2;
  uint32_t flags = htonl(0), count = htonl((uint32_t)(argc - 1));
  if (fwrite(&flags, sizeof(flags), 1, wire) != 1 ||
      fwrite(&count, sizeof(count), 1, wire) != 1)
    return 2;
  for (int i = 1; i < argc; i++) {
    size_t size = strlen(argv[i]);
    uint32_t length = htonl((uint32_t)size);
    if (fwrite(&length, sizeof(length), 1, wire) != 1 ||
        fwrite(argv[i], 1, size, wire) != size)
      return 2;
  }
  if (fflush(wire) || fseek(wire, 0, SEEK_SET))
    return 2;

  ds_req_t request;
  int accepted = recv_req(fileno(wire), &request);
  fclose(wire);
  if (accepted < 0) {
    free_req(&request);
    fputs("daemon: bad request\n", stderr);
    return 1;
  }
  if (request.argc < 3 || strcmp(request.argv[0], "--name=audio-fixture") ||
      strcmp(request.argv[1], "run")) {
    free_req(&request);
    return 2;
  }
  execvp(request.argv[2], request.argv + 2);
  free_req(&request);
  return 127;
}
