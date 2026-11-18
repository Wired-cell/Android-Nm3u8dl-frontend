#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>

typedef unsigned long (*openssl_version_num_fn)(void);

int main(void) {
    void *crypto = dlopen("libcrypto.so", RTLD_NOW | RTLD_LOCAL);
    if (!crypto) {
        fprintf(stderr, "dlopen libcrypto.so failed: %s\n", dlerror());
        return 10;
    }

    void *ssl = dlopen("libssl.so", RTLD_NOW | RTLD_LOCAL);
    if (!ssl) {
        fprintf(stderr, "dlopen libssl.so failed: %s\n", dlerror());
        dlclose(crypto);
        return 11;
    }

    dlerror();
    void *required_symbol = dlsym(ssl, "a2d_ASN1_OBJECT");
    const char *symbol_error = dlerror();
    if (symbol_error || !required_symbol) {
        fprintf(stderr, "Required OpenSSL symbol a2d_ASN1_OBJECT unavailable: %s\\n",
                symbol_error ? symbol_error : "unknown error");
        dlclose(ssl);
        dlclose(crypto);
        return 13;
    }

    dlerror();
    openssl_version_num_fn version =
        (openssl_version_num_fn)dlsym(ssl, "OpenSSL_version_num");
    const char *error = dlerror();
    if (error || !version) {
        fprintf(stderr, "OpenSSL_version_num unavailable: %s\n",
                error ? error : "unknown error");
        dlclose(ssl);
        dlclose(crypto);
        return 12;
    }

    printf("OpenSSL runtime loaded (version=0x%lx)\n", version());
    dlclose(ssl);
    dlclose(crypto);
    return 0;
}
