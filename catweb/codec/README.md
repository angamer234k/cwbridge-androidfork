# Codec

Import [decoder.json](./decoder.json) on **both** the CLIENT page and the SERVER page.

It is the File.rbx encoder/decoder (`text encoder from ver veq4DA`).

| function | arg | result |
|----------|-----|--------|
| `e` | plain text (`data`) | digit string |
| `d` | digit string (`data`) | plain text |

CLIENT and SERVER call `Run function (e)` / `Run function (d)` with one tuple argument. That argument is `data` (`{l!data}`).

Press F15 on a page that has this script to log `File.rbx: Decoder` (sanity check that it loaded).
