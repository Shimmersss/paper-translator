# Packaged tiktoken cache

`fb374d419588a4632f3f557e76b4b70aebbca790` is the `o200k_base.tiktoken`
vocabulary used by BabelDOC/tiktoken.

- Source: `https://openaipublic.blob.core.windows.net/encodings/o200k_base.tiktoken`
- Cache key: SHA-1 of the source URL
- SHA-256: `446a9538cb6c348e3516120d7c08b09f57c36495e2acfffe59a5bf8b0cfb1a2d`

The runner verifies the SHA-256 digest before using this file. Shipping it with
the application prevents production translation from depending on an Azure
download during each fresh runtime or host reboot.
