#!/usr/bin/env python3
"""Backend Papo falso, só para exercitar o app no emulador.

Implementa o subconjunto de auth/servidor da OpenAPI com sessão por cookie
`Auth`. Não substitui o backend real: serve para ver as telas funcionando.

Uso: fake_papo.py <porta> [senha_do_servidor]

Cargos e permissões (desligados por padrão: tudo liberado, como sempre, e `/roles` não existe).
`FAKE_PERMISSIONS` os liga com a regra do servidor real, lida no código dele (`internal/services/messages.go`,
`reactions.go`, `voice.go`, `internal/middleware/permissions.go`): `GET /roles`, `permissions[]` em `GET /channels`,
`roles` nos usuários, `owner_id` em `GET /server`, e 403 em ler, enviar, anexar, fixar, excluir mensagem alheia,
reagir e entrar na voz. O valor é `demo` (o cenário abaixo) ou o caminho de um JSON:

    {"owner": "dona",                                   # usuário dono (criado, com a senha Senha@000, se não existe)
     "roles": [{"name": "Moderação", "color": "#E86CA5", # cor opcional
                "permissions": ["pin_message", "everyone_message", "send_attachment"]}],
     "users": {"bia": ["Moderação"]},                    # cargos de cada usuário, por nome
     "channels": [{"name": "avisos",                     # canal criado (type: text ou voice) se não existe
                   "entries": {"Moderação": ["read_channel", "send_messages", "delete_messages", "connect_voice"]}}],
     "messages": [{"channel": "papo", "author": "bia", "content": "@everyone oi"}]}

Flags de cargo: manage_server, manage_channels, manage_roles, manage_webhooks, ban_members, pin_message,
everyone_message, send_attachment. Flags de canal: read_channel, send_messages, delete_messages, connect_voice.
Canal sem `entries` é aberto. O cenário `demo`: dono `dona` (Senha@000); `bia` Moderação, `ana` Leitores, `caio` sem
cargo; `avisos` (Leitores só lê), `equipe` (só Moderação) e a voz `Palco` (Leitores lê, mas não entra); `papo` e
"Sala de voz" abertos; `@everyone` de `bia` (pode), `caio` (não pode) e `dona` (dona) no `papo`.

Quem reagiu: cada reação guarda quando foi feita e um id, e `GET /channels/{id}/messages/{id}/reactions` pagina como o
servidor (100 reações por página, cursor `since` + `last_id`, `order`, grupos na ordem da primeira aparição). Com
`FAKE_MANY_REACTORS=1` o `papo` ganha uma mensagem com 130 usuários inventados ("Pessoa 001"…) reagindo com três emojis
intercalados: o grupo de cada emoji se parte entre as duas páginas, e os horários têm frações de tamanhos diferentes.

Para mudar ao vivo, entre como o dono e use as rotas da OpenAPI (com cookie), que mandam os eventos como o servidor:
`POST /roles`, `PUT`/`DELETE /roles/{id}` (estes dois, como no servidor, **não** mandam evento),
`POST /users/{id}/roles` e `DELETE /users/{id}/roles/{role_id}` (`role_add`/`role_remove`),
`PUT /channels/{id}/permissions/{role_id}` e `DELETE /channels/{id}/role/{role_id}` (`channel_update`).
Os ids dos cargos são fixos (derivados do nome) e saem na linha de partida, junto com os dos usuários.

Vídeos anexados (tudo desligado por padrão; sem estas variáveis o fake responde como sempre). `GET /attachments/{id}`
atende `Range` como o `http.ServeContent` do servidor: 206 com `Content-Range` para um trecho (`N-M`, `N-`, `-N`), 200 sem
`Range` (ou com vários trechos, que o fake não monta em multipart), 416 com `Content-Range: bytes */tamanho` para um
trecho fora do arquivo e 416 para um `Range` ilegível; sempre com `Accept-Ranges: bytes`. O log da linha de comando mostra
o `Range` e o status de cada pedido, para conferir que buscar no vídeo pede só o trecho novo.

    FAKE_VIDEO_DIR=<pasta>    semeia no `papo` uma mensagem de `bia` para cada .mp4, .webm e .ogv da pasta (com
                              largura e altura lidas pelo `ffprobe`, se existir) e mais quatro do menor arquivo:
                              sensível, em retrato (360x640), sem dimensões e sem dimensões com uma miniatura do servidor
                              (`thumbnail_id`, 96x64, que o servidor real só gera para imagem)
    FAKE_VIDEO_KBPS=500       entrega os vídeos a 500 KB/s, para o player ter o que bufferizar
    FAKE_VIDEO_401_ONCE=N     responde 401 uma vez, no N-ésimo pedido de vídeo desde a partida (1 = o primeiro): o
                              cliente renova a sessão (`POST /auth/refresh`) e repete com o cookie novo

Vídeos de embed (ligados por um link, sem variável). Uma mensagem com `https://videos.example.com/...` recebe, ~1 s
depois de criada, um `message_embeds_update` com um embed `opengraph`: título (o nome do arquivo do link), imagem
(`GET /embeds/{id}`, com `image_data`) e `video` com `mime_type` pela extensão (.webm, .ogv; senão `video/mp4`) e a URL
do link. `GET /embeds/{id}/video` é o relay: serve, com `Range` como o anexo, o vídeo semeado por `FAKE_VIDEO_DIR` de
mesmo nome que o fim do link, ou o primeiro do mesmo tipo; sem semente, 404 (o que o relay real faz com um vídeo que não
serve). O relay segue `FAKE_VIDEO_KBPS` e `FAKE_VIDEO_401_ONCE`, que contam os pedidos dele junto com os dos anexos.

    FAKE_RELAY_502=1          o relay responde 502 (falha na origem, ou as vagas globais do servidor cheias) a todo pedido

Embeds, GIFs e moderação (sempre ligados): o canal `embeds` traz embeds customizados de webhook (cor, autor com ícone em
`author_image_data`, campos `inline` e largos, rodapé, e um com links `javascript:`/`intent:`/`file:` que o app não deve abrir),
embeds de GIF (`fetch_method: gif`, Tenor e GIPHY, com a `thumbnail` animada), imagens de embed 1200x630, 600x900 e 512x512 sem e
com vídeo (e uma sem medidas) desenhadas com borda e cantos coloridos, para ver se algo é cortado, e anexos em cada estado de
moderação: GIF animado, GIF `sensitive`, `pending`, `blocked` (a lista já diz) e um que a lista ainda diz `pending` mas o
servidor bloqueou (o arquivo e a miniatura respondem 410). Os vídeos desses embeds tocam se houver `FAKE_VIDEO_DIR`.

YouTube (sempre ligado, no canal `embeds`): um embed `oembed` cujo player é `video.url` = `https://www.youtube.com/embed/<id>`, sem
`mime_type`, como o servidor o entrega, com a miniatura em `GET /embeds/{id}`; e um cujo endereço de player é quase o certo
(`youtube.com.evil.example.com`), que o app não pode tocar. O id é inventado, então o player real responde "vídeo indisponível"
(basta para ver o fluxo do app); `FAKE_YOUTUBE_ID=<11 caracteres>` põe um id de verdade, para ver um vídeo tocar. O relay
(`GET /embeds/{id}/video`) responde 404 a eles, como o servidor.

    FAKE_YOUTUBE_ID=<id>      o id do vídeo do YouTube semeado (11 caracteres de A-Z, a-z, 0-9, _ e -)

    FAKE_MODERATION=1         acrescenta a mensagem "moderação ao vivo": a imagem vira `sensitive` (evento
                              `attachment_moderation_update`) depois de 20 s e `blocked` (410, sem evento) depois de mais 20 s.
                              Outro número é o intervalo em segundos

Visualizador de mídia (sempre ligado): o canal `galeria` traz uma mensagem com quatro mídias (imagem larga, alta, quadrada e um
GIF), uma com uma imagem só, uma de 4800x3200 (para o zoom), uma com dois anexos sensíveis entre normais, uma com um anexo
bloqueado entre normais e, com `FAKE_VIDEO_DIR`, uma com imagem, vídeo, GIF e imagem juntos.

Áudios anexados (trilha A, onda 3). O canal `audio` traz sempre um áudio que o aparelho não decodifica (`audio/ogg` com bytes que
não são Ogg: o player diz "formato não suportado") e um que a lista diz limpo mas o servidor já bloqueou (o arquivo responde 410: o
cartão vira o aviso da moderação). `GET /attachments/{id}` atende `Range` para áudio como para vídeo, e o log da linha de comando
mostra o `Range` e o status de cada pedido, para conferir que buscar no áudio pede só o trecho novo.

    FAKE_AUDIO_DIR=<pasta>    semeia no `audio` uma mensagem de `bia` para cada .ogg, .opus, .mp3, .m4a, .wav e .webm da pasta, com
                              30 mensagens curtas depois da primeira (o cartão dela sai da tela e a barra de áudio aparece), e mais:
                              uma gravação de voz do web (`audio_gravado.<ext>` com o tipo `video/webm`, como o servidor a fareja), um
                              envio antigo (`antigo.<ext>` com o tipo genérico), um áudio sensível, dois áudios numa mensagem só
                              (para ver que só um toca por vez)

Renovação da sessão (sempre ligada). `POST /auth/refresh` rotaciona o token como o servidor (`RefreshConnection`): troca o
`Auth` por outro, com outro id de conexão e o mesmo usuário, no `Set-Cookie`. O token trocado ainda vale por 60 s (a janela de
graça do servidor); reapresentado depois disso é reuso: o servidor revoga todas as sessões do usuário e responde 401. Um
`refresh` com o token trocado devolve o token ativo da cadeia, sem rotacionar de novo.

    FAKE_REFRESH_GRACE=0      janela de graça em segundos (60 por padrão); 0 faz o token trocado ser reuso de imediato

Perfis e webhooks (sempre ligados). `GET /users/{id}/profile` (com sessão; 404 para um usuário que não existe) devolve o
perfil como a OpenAPI o descreve: `ana` ("Ana Lima", ocupada, cargo Design), `bia` (cargo Admin, avatar e banner) e `caio` (ausente)
têm recado e descrição; o banner vem de `GET /media/{sha}` (com sessão) e o avatar de `bia` também de `GET /users/{id}/avatar`.
As mensagens de webhook trazem sempre o mesmo `webhook_id` para o mesmo webhook (derivado do nome). No `papo`: "Robô de Deploy"
(sem avatar), "Monitor de Status" (duas mensagens seguidas, com avatar persistente em `/webhooks/{id}/avatar`, rota pública) e
"Maria da Ponte", com um autor temporário cujo `avatar_url` é uma URL externa que o app nunca deve pedir.

Editar o próprio perfil (sempre ligado; só o próprio usuário, senão 403). Cada rota valida como a OpenAPI diz e responde 400
`application/problem+json` com o motivo em `detail`; o que foi gravado volta em `GET /users/{id}/profile` e na lista de usuários:
`PUT /users/{id}` pede `nickname`, `status` (a frase de status) e `description` (limites 32, 64 e 512 em code points) e aceita `typing`
(até 64; ausente ou nulo não muda); `PUT /users/{id}/avatar` e `/banner` pedem base64 e formato (GIF, JPEG, JPG, PNG, WEBP), conferem
os bytes mágicos, no máximo 2 MiB e 512 px (avatar) ou 2048 px (banner); banner vazio remove, avatar vazio é recusado (a OpenAPI não
documenta remover o avatar); `PUT /users/{id}/status` aceita `away`, `busy` ou nulo. Corpo acima de 4 MiB: 413. Eventos: `presence_update`
(apelido, frase, digitação e status) depois do perfil e do status, `avatar_update` depois do avatar. O status escolhido pelo PUT vale na
próxima conexão (`presence_sync`); o `status` semeado de `ana` e `caio` só aparece no perfil.
"""
import base64
import hashlib
import json
import os
import random
import re
import struct
import subprocess
import sys
import threading
import time
import unicodedata
import uuid
import zlib
from datetime import datetime, timedelta, timezone
from email.parser import BytesParser
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1])
SERVER_PASSWORD = sys.argv[2] if len(sys.argv) > 2 else None
NAME = f"Boteco {PORT}" if SERVER_PASSWORD is None else f"Clube Fechado {PORT}"

USERS = {}      # username -> {id, password}
SESSIONS = {}   # token -> {user, id, created}: as sessões ativas
REPLACED = {}   # token trocado pelo refresh -> {session, at, tip}: a sessão dele, quando foi trocado e o token que o substituiu
REFRESH = {"grace": 60.0}   # FAKE_REFRESH_GRACE: segundos em que o token trocado ainda vale (RefreshGracePeriod do servidor)
SESSION_COOKIE = "Auth=%s; Path=/; HttpOnly; SameSite=Strict"


CHANNELS = [
    {"id": "c0000000-0000-4000-8000-000000000001", "name": "Geral", "type": "category", "position": 1},
    {"id": "c0000000-0000-4000-8000-000000000002", "name": "papo", "type": "text", "position": 2, "topic": "Conversa fiada",
     "parent_id": "c0000000-0000-4000-8000-000000000001"},
    {"id": "c0000000-0000-4000-8000-000000000003", "name": "avisos", "type": "text", "position": 3,
     "parent_id": "c0000000-0000-4000-8000-000000000001"},
    {"id": "c0000000-0000-4000-8000-000000000004", "name": "Sala de voz", "type": "voice", "position": 4,
     "parent_id": "c0000000-0000-4000-8000-000000000001"},
]
PAPO = CHANNELS[1]["id"]
MESSAGES = {}    # channel_id -> [message]
READS = {}       # (user_id, channel_id) -> message_id
DMS = {}         # dm_id -> (user_a, user_b)
PINS = {}        # channel_id -> [message_id]
FILES = {}       # attachment_id -> (mime, bytes)
THUMBS = {}      # attachment_id -> (mime, bytes): miniatura de um anexo que não é imagem (o servidor real só gera para imagem)
EMOJIS = []      # emojis do servidor, na ordem de criação: {id, name, format, image_blob, created_at}
EMOJI_BUCKETS = {}  # cookie -> [fichas, instante]: limite de taxa como o padrão do backend (10/s, rajada 20)
SOCKETS = []     # [(user_id, wfile, lock)]
LOCK = threading.RLock()
SEQ = [0]


def png(r, g, b, w=96, h=64):
    raw = b"".join(b"\x00" + bytes([r, g, b]) * w for _ in range(h))
    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


def noisy_png(r, g, b, size=96):
    """PNG com ruído, que quase não comprime: pesa como um emoji de verdade (~27 KB)."""
    rnd = random.Random(r * 65536 + g * 256 + b + size)
    raw = b"".join(b"\x00" + bytes(min(255, max(0, c + rnd.randint(-40, 40))) for _ in range(size) for c in (r, g, b)) for _ in range(size))
    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


def png_from_rows(w, h, raw):
    """PNG RGB de 8 bits a partir das linhas já montadas (cada uma começa com o byte de filtro 0)."""
    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(bytes(raw))) + chunk(b"IEND", b"")


def frame_png(w, h, hue=(35, 120, 200)):
    """PNG de w x h feito para denunciar corte e esticamento: borda branca, um quadrado em cada canto (vermelho e verde em
    cima, azul e amarelo embaixo), uma cruz branca no centro e o fundo em degradê da cor `hue` para o escuro. Quem o mostra
    cortado perde cantos; esticado, vê a cruz fora do centro ou os quadrados fora de proporção."""
    edge, corner, white = 8, 40, (255, 255, 255)
    corners = {"top": ((255, 0, 0), (0, 200, 0)), "bottom": ((0, 90, 255), (255, 210, 0))}

    def pixel(x, kind):
        if kind in ("edge", "center") or x < edge or x >= w - edge or abs(x - w // 2) < 2:
            return white
        if kind in corners:
            left, right = corners[kind]
            if x < corner:
                return left
            if x >= w - corner:
                return right
        shade = 1 - 0.6 * x / w
        return tuple(int(c * shade) for c in hue)

    def row_kind(y):
        if y < edge or y >= h - edge:
            return "edge"
        if abs(y - h // 2) < 2:
            return "center"
        return "top" if y < corner else "bottom" if y >= h - corner else "middle"

    rows, raw = {}, bytearray()
    for y in range(h):
        kind = row_kind(y)
        if kind not in rows:   # só cinco tipos de linha: o resto se repete
            rows[kind] = b"\x00" + b"".join(bytes(pixel(x, kind)) for x in range(w))
        raw += rows[kind]
    return png_from_rows(w, h, raw)


def animated_gif(w=320, h=180, frames=4, delay_cs=25):
    """GIF animado em laço de `frames` quadros: fundo que muda de cor e um quadrado branco que anda, com borda preta. Sem
    compressão (um código por pixel, com CLEAR antes de a tabela crescer), então o decodificador nunca muda o tamanho do
    código e não há o que errar na tabela; pesa uns 4,7 bits por pixel."""
    palette = [(0, 0, 0), (255, 255, 255), (231, 76, 60), (46, 204, 113), (52, 152, 219), (241, 196, 15), (155, 89, 182), (26, 188, 156)]
    bits = 3
    clear, end = 1 << bits, (1 << bits) + 1
    run = clear - 2   # códigos entre dois CLEAR sem o decodificador passar para o código maior

    def lzw(pixels):
        out, acc, used = bytearray(), 0, 0

        def put(code):
            nonlocal acc, used
            acc |= code << used
            used += bits + 1
            while used >= 8:
                out.append(acc & 0xFF)
                acc >>= 8
                used -= 8
        for start in range(0, len(pixels), run):
            put(clear)
            for pixel in pixels[start:start + run]:
                put(pixel)
        put(end)
        if used:
            out.append(acc & 0xFF)
        return bytes(out)

    def blocks(data):
        return b"".join(bytes([len(data[i:i + 255])]) + data[i:i + 255] for i in range(0, len(data), 255)) + b"\x00"

    out = bytearray(b"GIF89a" + struct.pack("<HHBBB", w, h, 0xF0 | (bits - 1), 0, 0))
    out += b"".join(bytes(color) for color in palette)
    out += b"\x21\xFF\x0BNETSCAPE2.0\x03\x01\x00\x00\x00"   # repetir sem fim
    for k in range(frames):
        background, left = 2 + k % 4, 8 + (w - 56) * k // max(1, frames - 1)
        pixels = bytearray()
        for y in range(h):
            row = bytearray([background]) * w
            if y < 4 or y >= h - 4:
                row[:] = bytes(w)
            else:
                row[:4] = row[-4:] = bytes(4)
                if h // 2 - 20 <= y < h // 2 + 20:
                    row[left:left + 40] = b"\x01" * 40
            pixels += row
        out += b"\x21\xF9\x04\x00" + struct.pack("<H", delay_cs) + b"\x00\x00"
        out += b"\x2C" + struct.pack("<HHHHB", 0, 0, w, h, 0) + bytes([bits]) + blocks(lzw(pixels))
    return bytes(out) + b"\x3B"


# Emojis animados do servidor falso: três quadros de 64x64 (um disco vermelho, amarelo e verde sobre fundo transparente),
# com menos de 1 KB cada. O GIF vem do Pillow (`save_all`, `loop=0`, `disposal=2`) e o WebP do ffmpeg
# (`-framerate 4 -i f%d.png -c:v libwebp_anim -lossless 1 -loop 0`).
ANIMATED_EMOJIS = {
    "party_gif": ("GIF", (
        "R0lGODlhQABAAIEAAAAAAP9GRgAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQJGQAAACwAAAAAQABAAAAI2AABCBxIsKDB"
        "gwgTKlzIsKHDhxAjSpxIsaLFixgzatzIsaPHjyARBhhJkmTIiSVTqjzZUKXLlSwPvpwJM6ZAmjhL2gSQs+fImD6DngxKFCTR"
        "ox6PKu2oFOnGpks1QnWKcWrUi1apWsxaFCvXrhW/gqUoVujWsj29osWpdu3Mtm5dwo2bci5dk3bpZrxb8yxfvFX/BngqmONf"
        "pnyT6v0YdyhaoGJ3ft150yplgk0vy/SpeeHbzqBDix5NujRlwVM3o76KeTXrga5fV45ttiDtsbBvczbNu3fogAAh+QQJGQAA"
        "ACwAAAAAQABAAIEAAAD/yCgAAAAAAAAI2AABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFixgzatzIsaPHjyAhBhhJkmTIiSVT"
        "qjzZUKXLlSwPvpwJM6ZAmjhL2gSQs+fImD6DngxKFCTRox6PKu2oFOnGpks1QnWKcWrUi1apWsxaFCvXrhW/gqUoVujWsj29"
        "osWpdu3Mtm5dwo2bci5dk3bpZrxb8yxfvFX/BngqmONfpnyT6v0YdyhaoGJ3ft150yplgk0vy/SpeeHbzqBDix5NujRjwVA3"
        "o76KeTXrga5fV45ttiDtsbBvczbNu3fogAAh+QQJGQAAACwAAAAAQABAAIEAAAA8yHgAAAAAAAAI2AABCBxIsKDBgwgTKlzI"
        "sKHDhxAjSpxIsaLFixgzatzIsaPHjyAxBhhJkmTIiSVTqjzZUKXLlSwPvpwJM6ZAmjhL2gSQs+fImD6DngxKFCTRox6PKu2o"
        "FOnGpks1QnUqcipVi1avVsxa9CLXrli/+qwqNifZsjTPon2pdm3NrW5nto1r0itdlxnvvoWrt67dvj/zAn46mPBdpnqT0jXq"
        "dihaoGJ3ft150yplgk0vyxyrWaHczqBDix5NuvRfwFELooa6ebXWyq7BYo4teyDt2rBvmzXNu3fogAA7"
    )),
    "wave_webp": ("WEBP", (
        "UklGRt4BAABXRUJQVlA4WAoAAAASAAAAPwAAPwAAQU5JTQYAAAD/////AABBTk1GjAAAAAIAAAQAADgAADQAAPoAAAJWUDhM"
        "cwAAAC84AA0QDzBG8z9G8x/wcBNJkiP1a3MhDJSDdgvtoCyEMV+X0fP6AUT0fwKkDJV3ig4bG9AsIewIk11htjtgACup2+aZ"
        "xQ7PDGvvSIt/MKy9Y7HDM902z8hWku6AJF1htiNMlhA2oFmHjemOyozqW6lX3wcAQU5NRowAAAACAAAEAAA4AAA0AAD6AAAC"
        "VlA4THMAAAAvOAANEA8wyPM/KPMf8HATSZIj9WtzIQyUg3YL7aAshDFfl9Hz+gFE9H8CJGWovFN02NiAZglhR5jsCrPdAQNY"
        "Sd02zyx2eGZYe0da/INh7R2LHZ7ptnlGtpJ0ByTpCrMdYbKEsAHNOmxMd1RmVF9Kvfo+AEFOTUaKAAAAAgAABAAAOwAANAAA"
        "+gAAAlZQOExyAAAALzsADRAPMMjDM3jzH/BwFEmS4/QZSc+FsFAO2i20g7IQ5ilfjx55AYjo/wRIUlSld5IGk3UoFlDtCItd"
        "YbU7YACD1Gx6ZrPDM93KO+Jv9Ldtdnim2fSMbJB0ByTpCqsdYbGAah2KNZhMd5RGzb6TfPg+"
    )),
}


# Tipos de vídeo que o servidor aceita como arquivo; o MIME do anexo é farejado no upload, aqui pela extensão.
VIDEO_TYPES = {".mp4": "video/mp4", ".webm": "video/webm", ".ogv": "video/ogg"}
GENERIC_TYPES = {"", "application/octet-stream"}
# Áudios que FAKE_AUDIO_DIR semeia: o MIME de cada extensão, como o servidor o fareja.
AUDIO_TYPES = {".ogg": "audio/ogg", ".opus": "audio/ogg", ".mp3": "audio/mpeg", ".m4a": "audio/mp4", ".wav": "audio/wav",
               ".webm": "audio/webm"}
VIDEO = {"kbps": 0.0, "expire_at": 0, "requests": 0}   # FAKE_VIDEO_KBPS, FAKE_VIDEO_401_ONCE e os pedidos de vídeo vistos
CHUNK = 16384
SEEDED = []     # vídeos semeados por FAKE_VIDEO_DIR: (nome, bytes, mime, largura, altura)

# Embeds de vídeo: um link do `videos.example.com` vira um embed depois de um atraso, como o crawl em segundo plano do servidor.
EMBED_LINK = re.compile(r"https://videos\.example\.com/[^\s<>\"]*", re.IGNORECASE)
EMBED_DELAY = 1.0
EMBEDS = {}     # embed_id -> {"view": o embed como o servidor o descreve, "channel_id", "image": bytes, "seed": vídeo semeado ou None}
RELAY = {"fail": False}   # FAKE_RELAY_502
GONE = set()    # anexos bloqueados pela moderação: o arquivo e a miniatura respondem 410, esteja a mensagem listando o estado ou não


def sniffed_mime(declared, file_name):
    """O MIME de um anexo: o declarado, ou, se ele é genérico, o do vídeo que a extensão do nome indica."""
    if declared in GENERIC_TYPES:
        return VIDEO_TYPES.get(os.path.splitext(file_name or "")[1].lower(), "application/octet-stream")
    return declared


def range_spec(spec, size):
    """Um item de `Range: bytes=`: (primeiro, último) inclusivos; "beyond" se cai fora do arquivo; None se não é legível."""
    found = re.fullmatch(r"(\d*)-(\d*)", spec, re.ASCII)
    if found is None or not any(found.groups()):
        return None
    first, last = found.groups()
    if not first:   # "-N": os N últimos bytes
        count = min(int(last), size)
        return (size - count, size - 1) if count else "beyond"
    if int(first) >= size:
        return "beyond"
    if last and int(last) < int(first):
        return None
    return int(first), min(int(last), size - 1) if last else size - 1


def byte_range(header, size):
    """Lê o `Range` de um pedido como o `http.ServeContent` do servidor (só a unidade `bytes`).

    Devolve (tipo, trecho): ("partial", (primeiro, último)) com os dois inclusivos; ("full", None) sem `Range` ou com
    vários trechos (o fake serve o arquivo inteiro, que o HTTP permite, em vez de montar multipart); ("unsatisfiable",
    None) se nenhum trecho cai dentro do arquivo; ("invalid", None) se o cabeçalho não é legível.
    """
    if not header:
        return "full", None
    if not header.startswith("bytes="):
        return "invalid", None
    spans, beyond = [], False
    for spec in (item.strip() for item in header[len("bytes="):].split(",") if item.strip()):
        found = range_spec(spec, size)
        if found is None:
            return "invalid", None
        if found == "beyond":
            beyond = True
        else:
            spans.append(found)
    if not spans:
        return ("unsatisfiable", None) if beyond else ("full", None)
    return ("partial", spans[0]) if len(spans) == 1 else ("full", None)


def video_dimensions(path):
    """Largura e altura do vídeo, lidas do contêiner pelo `ffprobe` (o servidor também as lê do contêiner); (None, None) sem ele."""
    try:
        done = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height",
                               "-of", "csv=p=0", str(path)], capture_output=True, text=True, timeout=20)
        width, height = done.stdout.strip().split(",")
        return int(width), int(height)
    except (OSError, ValueError, subprocess.SubprocessError):
        return None, None


def video_attachment(name, data, mime, width, height, moderation="clean", thumbnail=False):
    """Registra o arquivo de um vídeo e devolve o anexo como o servidor o descreve: sem miniatura (o servidor só a gera
    para imagem), com as dimensões. Com `thumbnail` o anexo ganha uma miniatura PNG de 96x64, só para o app mostrá-la."""
    aid = str(uuid.uuid4())
    FILES[aid] = (mime, data)
    if thumbnail:
        THUMBS[aid] = ("image/png", png(255, 178, 56))
    return {"id": aid, "mime_type": mime, "original_file_name": name, "size_bytes": len(data), "width": width, "height": height,
            "thumbnail_id": aid if thumbnail else None, "thumbnail_width": 96 if thumbnail else None,
            "thumbnail_height": 64 if thumbnail else None, "moderation_status": moderation}


def seed_videos(folder):
    """FAKE_VIDEO_DIR: uma mensagem de `bia` no `papo` para cada vídeo da pasta, e quatro variações dos metadados do menor."""
    root = Path(folder)
    files = sorted(p for p in root.iterdir() if p.suffix.lower() in VIDEO_TYPES) if root.is_dir() else []
    if not files:
        raise SystemExit("FAKE_VIDEO_DIR: nenhum .mp4, .webm ou .ogv em %s" % folder)
    loaded = []
    for path in files:
        width, height = video_dimensions(path)
        loaded.append((path.name, path.read_bytes(), VIDEO_TYPES[path.suffix.lower()], width, height))
        new_message(PAPO, uid("bia"), "vídeo %s" % path.name, attachments=[video_attachment(*loaded[-1])])
    SEEDED[:] = loaded
    # Do menor arquivo (e, empatando, do de nome mais curto): as variações não pesam quando alguém as toca.
    name, data, mime, width, height = min(loaded, key=lambda f: (len(f[1]), len(f[0])))
    for label, change in (("sensível", {"moderation": "sensitive"}), ("em retrato (360x640)", {"width": 360, "height": 640}),
                          ("sem dimensões", {"width": None, "height": None}),
                          ("com miniatura do servidor", {"width": None, "height": None, "thumbnail": True})):
        fields = {"width": width, "height": height, "moderation": "clean", **change}
        new_message(PAPO, uid("bia"), "vídeo %s %s" % (name, label), attachments=[video_attachment(name, data, mime, **fields)])


def video_settings():
    """Lê FAKE_VIDEO_KBPS e FAKE_VIDEO_401_ONCE; valor ilegível derruba o fake na partida, não no primeiro pedido."""
    try:
        VIDEO["kbps"] = float(os.environ.get("FAKE_VIDEO_KBPS") or 0)
        VIDEO["expire_at"] = int(os.environ.get("FAKE_VIDEO_401_ONCE") or 0)
    except ValueError as error:
        raise SystemExit("FAKE_VIDEO_KBPS e FAKE_VIDEO_401_ONCE: valor ilegível (%s)" % error)


def video_session_expires():
    """Conta um pedido de vídeo; é o que FAKE_VIDEO_401_ONCE manda responder 401."""
    with LOCK:
        VIDEO["requests"] += 1
        return VIDEO["requests"] == VIDEO["expire_at"]


def refresh_settings():
    """Lê FAKE_REFRESH_GRACE; valor ilegível derruba o fake na partida, não no primeiro refresh."""
    try:
        REFRESH["grace"] = float(os.environ.get("FAKE_REFRESH_GRACE") or REFRESH["grace"])
    except ValueError as error:
        raise SystemExit("FAKE_REFRESH_GRACE: valor ilegível (%s)" % error)


def open_session(username):
    """Registra uma conexão de sessão nova do usuário e devolve o token dela."""
    token = "jwt-" + uuid.uuid4().hex
    SESSIONS[token] = {"user": username, "id": str(uuid.uuid4()), "created": now()}
    return token


def session_of(token):
    """A sessão que o cookie `Auth` autentica, como o `CheckUserConnection` do servidor: o token ativo vale; o trocado pelo
    refresh vale só na janela de graça. Depois dela é reuso: o servidor revoga todas as sessões do usuário e recusa."""
    with LOCK:
        if token in SESSIONS:
            return SESSIONS[token]
        replaced = REPLACED.get(token)
        if replaced is None:
            return None
        if time.monotonic() - replaced["at"] < REFRESH["grace"]:
            return replaced["session"]
        user = replaced["session"]["user"]
        for revoked in [t for t, v in SESSIONS.items() if v["user"] == user]:
            del SESSIONS[revoked]
        sys.stderr.write("token trocado reapresentado fora da janela de graça: sessões de %s revogadas\n" % user)
        return None


def refresh_session(token):
    """O `RefreshConnection` do servidor: troca o token ativo por um novo (outro id de conexão, mesmo usuário) e devolve
    (token novo, sessão nova). Um token já trocado (refresh concorrente) devolve o ativo da cadeia, sem trocar de novo.
    Token desconhecido, ou de uma cadeia revogada, devolve None."""
    with LOCK:
        if token in SESSIONS:
            old = SESSIONS.pop(token)
            fresh = open_session(old["user"])
            REPLACED[token] = {"session": old, "at": time.monotonic(), "tip": fresh}
            return fresh, SESSIONS[fresh]
        while token in REPLACED:
            token = REPLACED[token]["tip"]
        return (token, SESSIONS[token]) if token in SESSIONS else None


def embed_seed(link):
    """O vídeo semeado que o relay serve para o link e o MIME que o embed anuncia: o de mesmo nome que o fim do link, senão o
    primeiro do tipo da extensão (mp4 se a extensão não é de vídeo). Sem semente, (None, mime)."""
    name = unquote(urlparse(link).path.rsplit("/", 1)[-1])
    mime = VIDEO_TYPES.get(os.path.splitext(name)[1].lower(), "video/mp4")
    seed = next((v for v in SEEDED if v[0] == name), None) or next((v for v in SEEDED if v[2] == mime), None)
    return seed, mime


def new_embed(channel_id, link):
    """Registra o embed de um link e devolve a forma que o servidor manda nas mensagens e no evento (sem `image_data`)."""
    seed, mime = embed_seed(link)
    name = unquote(urlparse(link).path.rsplit("/", 1)[-1])
    embed = {"id": str(uuid.uuid4()), "source_type": "link", "fetch_method": "opengraph", "provider": None,
             "site_name": "videos.example.com", "url": link, "title": name or "Vídeo de teste",
             "description": "Vídeo de teste, servido pelo relay do fake.",
             "thumbnail": {"mime_type": "image/png", "width": 320, "height": 180}, "image": None,
             "video": {"url": link, "mime_type": mime, "width": seed[3] if seed else None, "height": seed[4] if seed else None},
             "created_at": now(), "fetched_at": now()}
    EMBEDS[embed["id"]] = {"view": embed, "channel_id": channel_id, "image": png(35, 120, 200, 320, 180), "seed": seed}
    return embed


def publish_embeds(channel_id, message_id, links):
    """O fim do crawl: guarda os embeds na mensagem e avisa quem lê o canal, a menos que ela já tenha sido apagada."""
    with LOCK:
        message = next((m for m in MESSAGES.get(channel_id, []) if m["id"] == message_id), None)
        if message is None:
            return
        message["embeds"] = [new_embed(channel_id, link) for link in links]
        embeds = list(message["embeds"])
    emit({"type": "message_embeds_update", "channel_id": channel_id, "message_id": message_id, "embeds": embeds},
         only=audience(channel_id))


def crawl_links(message):
    """Agenda o embed dos links de vídeo da mensagem (os distintos, na ordem do texto); sem link, nada acontece."""
    links = list(dict.fromkeys(EMBED_LINK.findall(message["content"] or "")))
    if links:
        timer = threading.Timer(EMBED_DELAY, publish_embeds, (message["channel_id"], message["id"], links))
        timer.daemon = True
        timer.start()


def stamp():
    SEQ[0] += 1
    return (datetime(2026, 11, 24, 12, 0, 0, tzinfo=timezone.utc) + timedelta(seconds=SEQ[0])).isoformat().replace("+00:00", "Z")


def uid(name):
    return USERS[name]["id"]


def name_of(user_id):
    return next((n for n, u in USERS.items() if u["id"] == user_id), None)


def new_message(channel_id, author_id, content, reply_to=None, attachments=None, created_at=None, external_author=None, webhook_id=None):
    m = {"id": str(uuid.uuid4()), "channel_id": channel_id, "author_id": author_id, "content": content,
         "created_at": created_at or stamp(), "edited_at": None, "reply_to": reply_to,
         "webhook_id": (webhook_id or webhook_id_for(external_author["name"])) if external_author else None,
         "external_author": external_author,
         "attachments": attachments or [], "embeds": [], "reactions": {}, }
    MESSAGES.setdefault(channel_id, []).append(m)
    return m


def fold(text):
    return "".join(c for c in unicodedata.normalize("NFD", text.lower()) if unicodedata.category(c) != "Mn")


def search_messages(data, query, me):
    """POST /search como na OpenAPI. Devolve o corpo da resposta, ou None se o pedido é inválido.

    O backend real usa busca textual do Postgres em português (com radicais);
    aqui um termo casa quando aparece em qualquer lugar do texto, sem acento.
    """
    text = (data.get("text") or "").strip()
    author, channel_id, mention = data.get("author") or "", data.get("channel_id") or "", data.get("mention") or ""
    has, order, attached = data.get("has") or "", data.get("order") or "desc", data.get("contains_attachment")
    start, end = data.get("date_start") or "", data.get("date_end") or ""
    since, last_id = query.get("since", [""])[0], query.get("last_id", [""])[0]
    if not (text or author or channel_id or mention or has or start or end or attached is not None):
        return None
    if has not in ("", "link") or order not in ("asc", "desc") or bool(since) != bool(last_id):
        return None
    try:
        for value in (channel_id, mention):
            if value:
                uuid.UUID(value)
        first = datetime.strptime(start, "%Y-%m-%d").replace(tzinfo=timezone.utc) if start else None
        # date_end é inclusivo: o limite é o começo do dia seguinte, em UTC.
        limit = datetime.strptime(end, "%Y-%m-%d").replace(tzinfo=timezone.utc) + timedelta(days=1) if end else None
    except ValueError:
        return None
    if first and limit and first >= limit:
        return None

    # Sem channel_id, as conversas diretas ficam de fora; com ele, uma DM só entra se o usuário participa dela.
    if channel_id:
        channels = [channel_id] if channel_id not in DMS or me in DMS[channel_id] else []
    else:
        channels = [c["id"] for c in CHANNELS]
    # Com as permissões ligadas, só entram canais que o usuário lê (o servidor: dono, canal aberto ou cargo com leitura).
    channels = [c for c in channels if has_channel_flag(c, me, "read_channel")]
    names = {c["id"]: c["name"] for c in CHANNELS}
    terms = [fold(t) for t in text.split()]
    token = "@mention(<@%s>)" % mention.lower()
    found = []
    for cid in channels:
        for m in MESSAGES.get(cid, []):
            content = m["content"] or ""
            when = datetime.fromisoformat(m["created_at"].replace("Z", "+00:00"))
            hits = sum(1 for t in terms if t in fold(content))
            if (terms and not hits) or (author and m["author_id"] != author) or (mention and token not in content.lower()):
                continue
            if (has and not re.search(r"https?://", content, re.I)) or (first and when < first) or (limit and when >= limit):
                continue
            if attached is not None and bool(m["attachments"]) != attached:
                continue
            external = m["external_author"]
            found.append({"type": "message", "id": m["id"], "content": m["content"], "channel_id": cid,
                          "channel_name": names.get(cid, ""), "author_id": m["author_id"],
                          "author_username": name_of(m["author_id"]) or (external or {}).get("name"),
                          "webhook_id": m["webhook_id"], "external_author": external, "created_at": m["created_at"],
                          **({"score": round(hits / len(terms), 3)} if terms else {})})
    desc = order == "desc"
    found.sort(key=lambda r: (r["created_at"], r["id"]), reverse=desc)
    if since:
        found = [r for r in found if ((r["created_at"], r["id"]) < (since, last_id) if desc else (r["created_at"], r["id"]) > (since, last_id))]
    return {"results": found[:100], "has_more": len(found) > 100}


def view(m, user_id=None):
    out = {k: v for k, v in m.items() if k != "reactions"}
    # A chave de cada reação é (emoji_id, unicode); um dos dois é None. Os usuários: {user_id: (created_at, reaction_id)}.
    out["reactions"] = [{"emoji_id": k[0], "unicode": k[1], "count": len(us)} for k, us in m["reactions"].items() if us] or None
    out["user_reactions"] = [{"id": us[user_id][1], "emoji_id": k[0], "unicode": k[1]} for k, us in m["reactions"].items() if user_id in us]
    return out


def nano(moment):
    """Um instante UTC como o Go escreve RFC 3339 Nano: a fração sem zeros à direita, e nenhuma quando é zero."""
    fraction = ".%06d" % moment.microsecond if moment.microsecond else ""
    return moment.strftime("%Y-%m-%dT%H:%M:%S") + fraction.rstrip("0") + "Z"


def instant(text):
    """O instante de um RFC 3339 com `Z` e fração de 0 a 9 dígitos (como `nano` escreve). ValueError se não for."""
    found = re.fullmatch(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?Z", text or "")
    if not found:
        raise ValueError(text)
    return datetime.fromisoformat(found.group(1) + "." + (found.group(2) or "").ljust(6, "0")[:6] + "+00:00")


def react(message, key, user_id, at=None):
    """Põe a reação de `user_id` em `message` com o emoji `key`; reagir de novo não muda nada (a rota é idempotente)."""
    message["reactions"].setdefault(key, {}).setdefault(user_id, (at or nano(datetime.now(timezone.utc)), str(uuid.uuid4())))


REACTION_PAGE = 100


def reaction_page(message, query):
    """`GET .../reactions` como o servidor (`storage/reactions.go`): 100 linhas por página em (created_at, id), com o
    cursor exato `since` + `last_id`. `since` sem `last_id` filtra `created_at > since` em qualquer ordem, e `last_id`
    sem `since` é ignorado. Devolve o corpo, ou None se `order` ou `since` são inválidos."""
    order, since, last_id = query.get("order", ["desc"])[0], query.get("since", [""])[0], query.get("last_id", [""])[0]
    if order not in ("asc", "desc"):
        return None
    try:
        after = instant(since) if since else None
    except ValueError:
        return None
    rows = [(instant(at), rid, user_id, key, at) for key, users in message["reactions"].items() for user_id, (at, rid) in users.items()]
    rows.sort(reverse=order == "desc")
    if after is not None and last_id:
        rows = [r for r in rows if ((r[0], r[1]) < (after, last_id) if order == "desc" else (r[0], r[1]) > (after, last_id))]
    elif after is not None:
        rows = [r for r in rows if r[0] > after]
    page = rows[:REACTION_PAGE]
    groups = {}
    for _, rid, user_id, key, created_at in page:
        groups.setdefault(key, []).append({"id": rid, "user_id": user_id, "created_at": created_at})
    return {"message_id": message["id"], "has_more": len(rows) > REACTION_PAGE,
            "reactions": [{"emoji_id": k[0], "unicode": k[1], "count": len(u), "users": u} for k, u in groups.items()]}


def audience(channel_id):
    if channel_id in DMS:
        return set(DMS[channel_id])
    if ACCESS["on"]:
        # Como o servidor: o evento do canal só chega a quem lê o canal.
        return {u["id"] for u in USERS.values() if has_channel_flag(channel_id, u["id"], "read_channel")}
    return None  # todos


def frame(text):
    data = text.encode()
    head = b"\x81"
    if len(data) < 126:
        head += bytes([len(data)])
    elif len(data) < 65536:
        head += b"\x7e" + struct.pack(">H", len(data))
    else:
        head += b"\x7f" + struct.pack(">Q", len(data))
    return head + data


def emit(event, only=None):
    payload = frame(json.dumps(event))
    with LOCK:
        targets = [s for s in SOCKETS if only is None or s[0] in only]
    for user_id, wfile, lock in targets:
        try:
            with lock:
                wfile.write(payload)
                wfile.flush()
        except OSError:
            pass


def last_message(channel_id):
    ms = MESSAGES.get(channel_id) or []
    if not ms:
        return None
    m = ms[-1]
    return {"id": m["id"], "content": m["content"], "author_id": m["author_id"], "author_username": name_of(m["author_id"]), "created_at": m["created_at"]}


def summary(name):
    roles = role_summaries(uid(name)) if ACCESS["on"] else USERS[name].get("roles", [])
    return {"id": uid(name), "username": name, "nickname": USERS[name].get("nickname"), "banned": False,
            "created_at": USERS[name]["created"], "roles": roles}


def dm_view(dm_id, user_id):
    other = next(u for u in DMS[dm_id] if u != user_id) if DMS[dm_id][0] != DMS[dm_id][1] else user_id
    ms = MESSAGES.get(dm_id) or []
    read = READS.get((user_id, dm_id))
    ids = [m["id"] for m in ms]
    start = ids.index(read) + 1 if read in ids else 0
    unread = sum(1 for m in ms[start:] if m["author_id"] != user_id)
    return {"id": dm_id, "user": summary(name_of(other)), "created_at": "2026-11-24T11:00:00Z",
            "last_message": last_message(dm_id), "last_read_message": read, "unread_count": unread}


def now():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


# ── Cargos e permissões ──────────────────────────────────────────────────────────────────────────
# Ligados por FAKE_PERMISSIONS (ver o docstring). Desligados, `has_*` e `can_*` respondem sim e nada muda.

ROLE_FLAGS = ("manage_server", "manage_channels", "manage_roles", "manage_webhooks", "ban_members",
              "pin_message", "everyone_message", "send_attachment")
CHANNEL_FLAGS = ("read_channel", "send_messages", "delete_messages", "connect_voice")
ACCESS = {"on": False, "owner": None}  # owner: id do dono do servidor (None: servidor sem dono)
ROLES = {}       # role_id -> {id, name, color, permissions: {flag: bool}, created_at}
USER_ROLES = {}  # user_id -> [role_id], na ordem da atribuição
ENTRIES = {}     # channel_id -> {role_id: {flag: bool}}: `permissions` do canal; sem entradas, o canal é aberto

DEMO = {
    "owner": "dona",
    "roles": [
        {"name": "Moderação", "color": "#E86CA5", "permissions": ["pin_message", "everyone_message", "send_attachment"]},
        {"name": "Leitores", "color": "#5B8DEF", "permissions": []},
    ],
    "users": {"bia": ["Moderação"], "ana": ["Leitores"]},
    "channels": [
        {"name": "avisos", "entries": {"Leitores": ["read_channel"],
                                        "Moderação": ["read_channel", "send_messages", "delete_messages"]}},
        {"name": "equipe", "entries": {"Moderação": ["read_channel", "send_messages"]}},
        {"name": "Palco", "type": "voice", "entries": {"Leitores": ["read_channel"],
                                                       "Moderação": ["read_channel", "connect_voice"]}},
    ],
    "messages": [
        {"channel": "papo", "author": "bia", "content": "@everyone reunião às 15h, quem puder passa na sala de voz."},
        {"channel": "papo", "author": "caio", "content": "@everyone pizza grátis na copa!"},
        {"channel": "papo", "author": "dona", "content": "Bom dia, @EVERYONE: a manutenção de sábado foi adiada."},
        {"channel": "papo", "author": "bia", "content": "em código `@everyone` não destaca"},
        {"channel": "avisos", "author": "bia", "content": "@everyone o aviso fixo mudou, leiam com calma."},
        {"channel": "equipe", "author": "bia", "content": "Pauta da reunião de equipe: só quem tem o cargo lê isto."},
    ],
}


def role_summaries(user_id):
    return [{"id": r["id"], "name": r["name"], "color": r["color"]} for r in my_roles(user_id)]


def my_roles(user_id):
    return [ROLES[r] for r in USER_ROLES.get(user_id, []) if r in ROLES]


def role_view(role):
    return {"id": role["id"], "name": role["name"], "color": role["color"],
            "permissions": dict(role["permissions"]), "created_at": role["created_at"]}


def entry_views(channel_id):
    entries = ENTRIES.get(channel_id, {})
    return [{"role_id": rid, "role_name": ROLES[rid]["name"], "permissions": dict(flags)}
            for rid, flags in sorted(entries.items(), key=lambda e: ROLES[e[0]]["name"])]


def new_role(name, color, flags, role_id=None):
    role = {"id": role_id or str(uuid.uuid4()), "name": name, "color": color,
            "permissions": {f: f in flags for f in ROLE_FLAGS}, "created_at": now()}
    ROLES[role["id"]] = role
    return role


def has_role_flag(user_id, flag):
    """userHasRolePermission: o dono tem tudo; os outros precisam de um cargo com a flag (não há cargo padrão)."""
    if not ACCESS["on"]:
        return True
    return user_id == ACCESS["owner"] or any(r["permissions"][flag] for r in my_roles(user_id))


def has_channel_flag(channel_id, user_id, flag, free_if_open=True):
    """userHasChannelPermission. Em DM: só membro, e `free_if_open=False` (excluir) nega até o dono. Em canal: o dono
    sempre; canal aberto (sem entradas) é livre se `free_if_open`; senão a união das entradas dos cargos do usuário."""
    if not ACCESS["on"]:
        return True
    if channel_id in DMS:
        return free_if_open and user_id in DMS[channel_id]
    if user_id == ACCESS["owner"]:
        return True
    entries = ENTRIES.get(channel_id, {})
    if free_if_open and not entries:
        return True
    return any(entries.get(r["id"], {}).get(flag) for r in my_roles(user_id))


def can_pin(channel_id, user_id, unpin=False):
    """Fixar: membro em DM, `pin_message` em canal. Desafixar em canal pede também leitura."""
    if channel_id in DMS:
        return has_channel_flag(channel_id, user_id, "read_channel")
    return has_role_flag(user_id, "pin_message") and (not unpin or has_channel_flag(channel_id, user_id, "read_channel"))


def can_delete_other(channel_id, user_id):
    """Excluir mensagem de outra pessoa: `delete_messages` de canal, que o canal aberto não dá (só o dono) e a DM nega a todos."""
    return ACCESS["on"] and has_channel_flag(channel_id, user_id, "delete_messages", free_if_open=False)


def permission_spec(value):
    if value == "demo":
        return DEMO
    try:
        with open(value) as f:
            return json.load(f)
    except (OSError, ValueError) as error:
        raise SystemExit("FAKE_PERMISSIONS: não consegui ler %s: %s" % (value, error))


def setup_access(spec):
    """Liga as permissões e semeia dono, cargos, cargos dos usuários, entradas dos canais e mensagens de `spec`."""
    def fail(text):
        raise SystemExit("FAKE_PERMISSIONS: " + text)

    def user_id(name):
        if name not in USERS:
            USERS[name] = {"id": str(uuid.uuid4()), "password": "Senha@000", "created": "2026-01-04T00:00:00Z"}
        return USERS[name]["id"]

    def check(flags, allowed, what):
        for flag in flags:
            if flag not in allowed:
                fail("flag de %s desconhecida: %s" % (what, flag))
        return flags

    ACCESS["on"] = True
    ACCESS["owner"] = user_id(spec["owner"]) if spec.get("owner") else None
    by_name = {}
    for item in spec.get("roles", []):
        flags = check(item.get("permissions", []), ROLE_FLAGS, "cargo")
        # Id derivado do nome: o mesmo em toda partida, para os curl de teste não dependerem de uma consulta.
        role = new_role(item["name"], item.get("color"), flags, str(uuid.uuid5(uuid.NAMESPACE_DNS, "papo-fake-role-" + item["name"])))
        by_name[role["name"]] = role
    for name, role_names in spec.get("users", {}).items():
        unknown = [r for r in role_names if r not in by_name]
        if unknown:
            fail("cargo desconhecido em users.%s: %s" % (name, unknown[0]))
        USER_ROLES[user_id(name)] = [by_name[r]["id"] for r in role_names]
    category = next((c for c in CHANNELS if c["type"] == "category"), None)
    for item in spec.get("channels", []):
        channel = next((c for c in CHANNELS if c["name"] == item["name"]), None)
        if channel is None:
            channel = {"id": str(uuid.uuid4()), "name": item["name"], "type": item.get("type", "text"),
                       "position": max(c["position"] for c in CHANNELS) + 1}
            if category:
                channel["parent_id"] = category["id"]
            CHANNELS.append(channel)
        entries = {}
        for role_name, flags in item.get("entries", {}).items():
            if role_name not in by_name:
                fail("cargo desconhecido em channels.%s: %s" % (item["name"], role_name))
            check(flags, CHANNEL_FLAGS, "canal")
            entries[by_name[role_name]["id"]] = {f: f in flags for f in CHANNEL_FLAGS}
        if entries:
            ENTRIES[channel["id"]] = entries
    for item in spec.get("messages", []):
        channel = next((c for c in CHANNELS if c["name"] == item["channel"]), None)
        if channel is None:
            fail("canal desconhecido em messages: %s" % item["channel"])
        new_message(channel["id"], user_id(item["author"]), item["content"])


def announce_access():
    print("Permissões ligadas. dono=%s" % (name_of(ACCESS["owner"]) or "(ninguém)"), flush=True)
    for role in ROLES.values():
        print("  cargo %s %s" % (role["id"], role["name"]), flush=True)
    for name in USERS:
        print("  usuário %s %s" % (uid(name), name), flush=True)


def send_control():
    """Como o fake responde a `POST /messages`, para exercitar a fila de envio do app.

    Vem do arquivo JSON em FAKE_SEND_CONTROL, lido a cada pedido (dá para mudar sem reiniciar o fake e
    perder as sessões), ou das variáveis de ambiente. Sem nada disso, responde como sempre.
      delay   FAKE_SEND_DELAY   segundos de espera antes de responder (depois de ler o corpo)
      status  FAKE_SEND_STATUS  responde com este erro HTTP (429, 500, 403...) em vez de criar a mensagem
      rate    FAKE_SEND_RATE    KB/s com que o corpo é lido: um anexo grande mostra o progresso do envio
      create  FAKE_SEND_CREATE  1: cria a mensagem e avisa pelo WebSocket, mas responde o erro de `status`
    """
    control = {}
    path = os.environ.get("FAKE_SEND_CONTROL")
    if path:
        try:
            with open(path) as f:
                control = json.load(f)
        except (OSError, ValueError):
            control = {}

    def pick(key, env):
        return control.get(key, os.environ.get(env, 0))

    return {"delay": float(pick("delay", "FAKE_SEND_DELAY")), "status": int(pick("status", "FAKE_SEND_STATUS")),
            "rate": float(pick("rate", "FAKE_SEND_RATE")), "create": bool(int(pick("create", "FAKE_SEND_CREATE")))}


# ── Perfis, mídia e webhooks ─────────────────────────────────────────────────────────────────────
# `GET /users/{id}/profile`, `GET /media/{sha}` (o banner), `GET /users/{id}/avatar` (de quem tem avatar) e
# `GET /webhooks/{id}/avatar`. Tudo o que está aqui sobre eles fica junto; o resto do fake só chama `serve_profiles`.

PROFILES = {}    # nome do usuário -> {description, status, status_message, banner (sha), avatar (bytes)}
MEDIA = {}       # sha256 em hexadecimal -> (mime, bytes): a mídia content-addressable, onde moram os banners
WEBHOOKS = {}    # webhook_id -> {name, avatar (bytes ou None)}: o avatar persistente do webhook, se tem


def webhook_id_for(name):
    """O id do webhook de um autor externo: derivado do nome, o mesmo em toda mensagem dele e em toda partida."""
    return str(uuid.uuid5(uuid.NAMESPACE_DNS, "papo-fake-webhook-" + name))


def add_webhook(name, avatar=None):
    """Registra o webhook `name` (com avatar persistente se `avatar` são os bytes de uma imagem) e devolve o id dele."""
    webhook_id = webhook_id_for(name)
    WEBHOOKS[webhook_id] = {"name": name, "avatar": avatar}
    return webhook_id


def add_media(data, mime="image/png"):
    """Guarda `data` na mídia e devolve o sha256 dele, a referência que o perfil leva em `banner_media`."""
    sha = hashlib.sha256(data).hexdigest()
    MEDIA[sha] = (mime, data)
    return sha


def seed_profiles():
    """Dá apelido, cargo, recado, descrição, banner e avatar a `ana`, `bia` e `caio`, para o perfil ter o que mostrar."""
    USERS["ana"]["nickname"] = "Ana Lima"
    USERS["ana"]["roles"] = [{"id": str(uuid.uuid5(uuid.NAMESPACE_DNS, "papo-fake-role-Design")), "name": "Design", "color": "#5B8DEF"}]
    PROFILES["ana"] = {"status": "busy", "status_message": "Em reunião até as 15h",
                       "description": "Desenho telas e ouço muito rock.\nPeça um protótipo, não um print."}
    PROFILES["bia"] = {"status": None, "status_message": "Revisando o deploy", "banner": add_media(png(70, 90, 200, 300, 100)),
                       "avatar": png(232, 108, 165, 96, 96),
                       "description": "Cuido da infraestrutura e do café.\nDocs: https://papo.example.com/bia (isto é só texto, não um link)."}
    PROFILES["caio"] = {"status": "away", "status_message": "De férias", "description": "Volto na segunda."}


def seed_webhooks():
    """No `papo`: o webhook de avatar persistente (duas mensagens seguidas), e um autor temporário com avatar externo.
    O "Robô de Deploy", sem avatar, já tem a mensagem dele no começo do histórico."""
    add_webhook("Robô de Deploy")
    monitor = add_webhook("Monitor de Status", avatar=png(60, 180, 113, 96, 96))
    persistent = {"name": "Monitor de Status", "avatar_url": "/webhooks/%s/avatar" % monitor}
    new_message(PAPO, None, "Todos os serviços respondem.", external_author=persistent)
    new_message(PAPO, None, "Latência normal: 42 ms.", external_author=persistent)
    add_webhook("Maria da Ponte")
    new_message(PAPO, None, "Mensagem encaminhada de outro chat.",
                external_author={"name": "Maria da Ponte", "avatar_url": "https://avatars.example.com/maria.png"})


def profile_view(name):
    """O perfil de `name` como a OpenAPI (`UserProfile`) o descreve; sem avatar, `avatar_blob` e `avatar_format` não vão."""
    user, extra = USERS[name], PROFILES.get(name, {})
    avatar = ({"avatar_blob": base64.b64encode(extra["avatar"]).decode(), "avatar_format": extra.get("avatar_format", "PNG")}
              if extra.get("avatar") else {})
    return {"id": user["id"], "username": name, "nickname": user.get("nickname"), **avatar,
            "banner_media": extra.get("banner"), "description": extra.get("description"), "status": extra.get("status"),
            "status_message": extra.get("status_message"), "typing": extra.get("typing"), "status_updated_at": None,
            "created_at": user["created"], "roles": summary(name)["roles"]}


def serve_profiles(handler, path):
    """Responde as rotas de perfil, mídia e avatar de webhook; devolve False se `path` não é uma delas. O avatar de um
    webhook é público (`security: []` na OpenAPI); o perfil e a mídia pedem a sessão, como os outros GET autenticados."""
    found = re.fullmatch(r"/webhooks/([^/]+)/avatar", path)
    if found:
        avatar = (WEBHOOKS.get(found.group(1)) or {}).get("avatar")
        if avatar is None:
            handler.problem(404, "Recurso não encontrado", "webhook sem avatar")
        else:
            handler.serve("image/png", avatar, 0)
        return True
    found = re.fullmatch(r"/users/([^/]+)/avatar", path)
    if found:
        avatar = PROFILES.get(name_of(found.group(1)) or "", {}).get("avatar")
        if avatar is None:
            return False   # quem não tem avatar segue a rota de sempre, que responde 404
        handler.serve(PROFILES[name_of(found.group(1))].get("avatar_mime", "image/png"), avatar, 0)
        return True
    profile = re.fullmatch(r"/users/([^/]+)/profile", path)
    media = re.fullmatch(r"/media/([^/]+)", path)
    if not (profile or media):
        return False
    if session_of(handler.cookie()) is None:
        handler.problem(401, "Não autorizado", "Token ausente, inválido ou expirado")
    elif profile and name_of(profile.group(1)) is not None:
        handler.send(200, profile_view(name_of(profile.group(1))))
    elif media and media.group(1) in MEDIA:
        handler.serve(*MEDIA[media.group(1)], 0)
    else:
        handler.problem(404, "Recurso não encontrado", "usuário não encontrado" if profile else "mídia não encontrada")
    return True


# ── Editar o próprio perfil ──────────────────────────────────────────────────────────────────────
# `PUT /users/{id}`, `/avatar`, `/banner` e `/status`, na forma da OpenAPI. Tudo sobre eles fica aqui; o resto do fake só chama
# `serve_profile_writes` (de `chat_write`) e `presence_of` (do WebSocket).

CHOSEN = {}   # nome do usuário -> "away" ou "busy": o status escolhido por `PUT /users/{id}/status` (vale na próxima conexão)
PROFILE_LIMITS = {"nickname": 32, "status": 64, "description": 512, "typing": 64}   # code points, como o `utf8.RuneCountInString` do servidor
IMAGE_FORMATS = {"GIF", "JPEG", "JPG", "PNG", "WEBP"}
IMAGE_MIMES = {"GIF": "image/gif", "JPEG": "image/jpeg", "PNG": "image/png", "WEBP": "image/webp"}
IMAGE_MAX_BYTES = 2 * 1024 * 1024
IMAGE_MAX_SIDE = {"avatar": 512, "banner": 2048}
BODY_MAX_BYTES = 4 * 1024 * 1024   # `middleware/bodylimit.go`


def presence_of(name):
    """O status que o usuário mostra enquanto está online: o que escolheu (away, busy) ou online."""
    return CHOSEN.get(name, "online")


def emit_presence(name):
    """O `presence_update` de `name` com o que o perfil guarda dele, para todos; offline se não há conexão aberta."""
    with LOCK:
        online = any(entry[0] == uid(name) for entry in SOCKETS)
    extra = PROFILES.get(name, {})
    emit({"type": "presence_update", "user_id": uid(name), "status": presence_of(name) if online else "offline",
          "status_message": extra.get("status_message"), "typing": extra.get("typing"), "nickname": USERS[name].get("nickname")})


def image_info(data):
    """(formato, largura, altura) de uma imagem GIF, JPEG, PNG ou WEBP pelos bytes mágicos; None se não é nenhuma delas ou está cortada."""
    try:
        if data[:6] in (b"GIF87a", b"GIF89a"):
            return "GIF", *struct.unpack("<HH", data[6:10])
        if data[:8] == b"\x89PNG\r\n\x1a\n":
            return "PNG", *struct.unpack(">II", data[16:24])
        if data[:2] == b"\xff\xd8":
            at = 2
            while at + 9 < len(data):
                if data[at] != 0xFF:
                    return None
                marker, length = data[at + 1], struct.unpack(">H", data[at + 2:at + 4])[0]
                if marker in range(0xC0, 0xD0) and marker not in (0xC4, 0xC8, 0xCC):   # SOF: altura e largura
                    height, width = struct.unpack(">HH", data[at + 5:at + 9])
                    return "JPEG", width, height
                at += 2 + length
            return None
        if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
            chunk = data[12:16]
            if chunk == b"VP8X":
                return "WEBP", 1 + int.from_bytes(data[24:27], "little"), 1 + int.from_bytes(data[27:30], "little")
            if chunk == b"VP8L":
                bits = int.from_bytes(data[21:25], "little")
                return "WEBP", 1 + (bits & 0x3FFF), 1 + ((bits >> 14) & 0x3FFF)
            if chunk == b"VP8 ":
                width, height = struct.unpack("<HH", data[26:30])
                return "WEBP", width & 0x3FFF, height & 0x3FFF
    except struct.error:
        return None
    return None


def check_image(field, encoded, declared, kind):
    """Valida um avatar ou banner como o servidor: base64, formato declarado, bytes mágicos, tamanho e lado. Devolve
    (bytes, formato, mime) ou o texto do 400."""
    if not isinstance(encoded, str) or not isinstance(declared, str):
        return "%s e %s_format devem ser texto" % (field, field)
    if declared.upper() not in IMAGE_FORMATS:
        return "%s_format deve ser GIF, JPEG, JPG, PNG ou WEBP" % field
    try:
        data = base64.b64decode(encoded, validate=True)
    except ValueError:
        return "%s não é base64 válido" % field
    if len(data) > IMAGE_MAX_BYTES:
        return "%s passa de 2MB" % field
    info = image_info(data)
    if info is None:
        return "%s não é uma imagem GIF, JPEG, PNG ou WEBP" % field
    kind_found, width, height = info
    if max(width, height) > IMAGE_MAX_SIDE[field]:
        return "%s passa de %dpx (tem %dx%d)" % (field, IMAGE_MAX_SIDE[field], width, height)
    return data, kind_found, IMAGE_MIMES[kind_found]


def update_profile_text(name, data):
    """O corpo de `PUT /users/{id}`: devolve None se gravou ou o texto do 400."""
    for field in ("nickname", "status", "description"):
        if not isinstance(data.get(field), str):
            return "%s é obrigatório" % field
    if data.get("typing") is not None and not isinstance(data["typing"], str):
        return "typing deve ser texto"
    for field, limit in PROFILE_LIMITS.items():
        value = data.get(field)
        if isinstance(value, str) and len(value) > limit:
            return "%s passa de %d caracteres (tem %d)" % (field, limit, len(value))
    USERS[name]["nickname"] = data["nickname"] or None
    extra = PROFILES.setdefault(name, {})
    extra["status_message"] = data["status"] or None
    extra["description"] = data["description"] or None
    if data.get("typing") is not None:
        extra["typing"] = data["typing"] or None
    return None


def serve_profile_writes(handler, method, path, me):
    """Responde os PUT do próprio perfil; devolve False se `path` não é um deles."""
    found = re.fullmatch(r"/users/([^/]+)(|/avatar|/banner|/status)", path)
    if method != "PUT" or not found:
        return False
    target, part = found.groups()
    if target != me:
        handler.forbidden("só o próprio usuário pode alterar o perfil")
        return True
    if int(handler.headers.get("Content-Length") or 0) > BODY_MAX_BYTES:
        handler.close_connection = True   # o corpo não é lido: a conexão não serve mais
        handler.problem(413, "Corpo grande demais", "o corpo passa de 4MB")
        return True
    data, name = handler.body(), name_of(me)
    extra = PROFILES.setdefault(name, {})
    problem = None
    if part == "":
        problem = update_profile_text(name, data)
        if problem is None:
            emit_presence(name)
    elif part == "/status":
        value = data.get("status")
        if value not in (None, "away", "busy"):
            problem = "status deve ser away, busy ou null"
        else:
            extra["status"] = value
            if value is None:
                CHOSEN.pop(name, None)
            else:
                CHOSEN[name] = value
            emit_presence(name)
    elif part == "/banner" and data.get("banner") == "":
        extra.pop("banner", None)   # vazio remove, sem evento (o banner não tem)
    else:
        field = part[1:]
        checked = check_image(field, data.get(field), data.get(field + "_format"), field)
        if isinstance(checked, str):
            problem = checked
        elif field == "avatar":
            extra["avatar"], extra["avatar_format"], extra["avatar_mime"] = checked[0], checked[1], checked[2]
            emit({"type": "avatar_update", "user_id": me})
        else:
            extra["banner"] = add_media(checked[0], checked[2])
    if problem is None:
        handler.send(200, {"response": "ok"})
    else:
        handler.problem(400, "Parâmetro inválido", problem)
    return True


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        wanted = self.headers.get("Range") if getattr(self, "headers", None) else None
        # Com `Range` o pedido é de vídeo: o trecho e o status mostram que buscar não baixa o começo de novo.
        status = " -> %s" % args[1] if wanted and len(args) > 1 else ""
        sys.stderr.write("%s %s%s%s\n" % (self.command, self.path, " Range: " + wanted if wanted else "", status))

    def cookie(self):
        for part in self.headers.get("Cookie", "").split(";"):
            name, _, value = part.strip().partition("=")
            if name == "Auth":
                return value
        return None

    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        try:
            return json.loads(raw or b"{}")
        except ValueError:
            return {}

    def send(self, status, payload=None, cookie=None):
        data = b"" if payload is None else json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("X-Request-ID", str(uuid.uuid4()))
        if payload is not None:
            kind = "application/problem+json" if status >= 400 else "application/json"
            self.send_header("Content-Type", kind)
        if cookie is not None:
            self.send_header("Set-Cookie", cookie)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def problem(self, status, title, detail):
        self.send(status, {"type": "https://papo.com/errors/x", "title": title, "status": status, "detail": detail, "instance": self.path})

    def forbidden(self, detail):
        self.problem(403, "Acesso negado", detail)

    def session(self):
        s = session_of(self.cookie())
        if s is None:
            self.problem(401, "Não autorizado", "Token ausente, inválido ou expirado")
        return s

    def attachment(self, file_id, thumbnail=False):
        """`GET /attachments/{id}` como o `http.ServeContent` do servidor: 200, 206, 416 e `Accept-Ranges`. Vídeo pode ser
        entregue devagar (FAKE_VIDEO_KBPS) e ter um 401 simulado (FAKE_VIDEO_401_ONCE). Com `thumbnail` (`/thumbnail`) serve a
        miniatura própria do anexo, se ele tem uma; senão o próprio arquivo, como o fake sempre fez para imagens. Anexo bloqueado
        pela moderação (em `GONE`) responde 410, arquivo ou miniatura."""
        if file_id in GONE:
            return self.problem(410, "Recurso indisponível", "arquivo removido pela moderação de imagens")
        mime, data = THUMBS[file_id] if thumbnail and file_id in THUMBS else FILES[file_id]
        video = mime.startswith("video/")
        if video and video_session_expires():
            sys.stderr.write("401 simulado por FAKE_VIDEO_401_ONCE\n")
            return self.problem(401, "Não autorizado", "Token ausente, inválido ou expirado")
        self.serve(mime, data, VIDEO["kbps"] if video else 0)

    def embed(self, embed_id, relay, me):
        """`GET /embeds/{id}` (o embed com `image_data`) e `GET /embeds/{id}/video` (o relay, que serve o vídeo semeado com
        `Range`). Embed de canal que o usuário não lê, sem vídeo semeado ou desconhecido é 404; FAKE_RELAY_502 faz o relay responder 502."""
        if relay and video_session_expires():
            sys.stderr.write("401 simulado por FAKE_VIDEO_401_ONCE\n")
            return self.problem(401, "Não autorizado", "Token ausente, inválido ou expirado")
        found = EMBEDS.get(embed_id)
        if found is None or not has_channel_flag(found["channel_id"], me, "read_channel") or (relay and found["seed"] is None):
            return self.problem(404, "Recurso não encontrado", "vídeo de embed não encontrado" if relay else "embed não encontrado")
        if not relay:
            encoded = lambda data: base64.b64encode(data).decode() if data else None
            return self.send(200, dict(found["view"], image_data=encoded(found["image"]), author_image_data=encoded(found.get("author_image"))))
        if RELAY["fail"]:
            return self.problem(502, "Mídia indisponível", "falha ao carregar vídeo do embed")
        _, data, mime, _, _ = found["seed"]
        self.serve(mime, data, VIDEO["kbps"])

    def serve(self, mime, data, kbps):
        """Responde com `data` como o `http.ServeContent`: 200, 206 ou 416, e `Accept-Ranges`; a `kbps` KB/s se for maior que 0."""
        size = len(data)
        kind, span = byte_range(self.headers.get("Range", ""), size)
        if kind in ("invalid", "unsatisfiable"):
            text = b"invalid range" if kind == "invalid" else b"invalid range: failed to overlap"
            self.send_response(416)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            if kind == "unsatisfiable":
                self.send_header("Content-Range", "bytes */%d" % size)
            self.send_header("Content-Length", str(len(text)))
            self.end_headers()
            self.wfile.write(text)
            return
        first, last = span if kind == "partial" else (0, size - 1)
        self.send_response(206 if kind == "partial" else 200)
        self.send_header("Content-Type", mime)
        self.send_header("Accept-Ranges", "bytes")
        if kind == "partial":
            self.send_header("Content-Range", "bytes %d-%d/%d" % (first, last, size))
        self.send_header("Content-Length", str(last - first + 1))
        self.end_headers()
        self.stream(data, first, last + 1, kbps)

    def stream(self, data, start, stop, kbps):
        """Escreve data[start:stop], no ritmo de `kbps` KB/s se for maior que 0. Quem desiste no meio (buscar em outro ponto
        fecha a conexão) só encerra esta resposta."""
        chunk = min(CHUNK, max(1024, int(kbps * 1024 / 20))) if kbps > 0 else CHUNK
        begin, sent = time.monotonic(), 0
        try:
            for offset in range(start, stop, chunk):
                piece = data[offset:min(offset + chunk, stop)]
                self.wfile.write(piece)
                sent += len(piece)
                if kbps > 0:
                    time.sleep(max(0.0, begin + sent / (kbps * 1024) - time.monotonic()))
        except OSError:
            self.close_connection = True

    def websocket(self):
        s = session_of(self.cookie())
        if s is None:
            return self.problem(401, "Não autorizado", "Token ausente, inválido ou expirado")
        me = uid(s["user"])
        accept = base64.b64encode(hashlib.sha1((self.headers["Sec-WebSocket-Key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        entry = (me, self.wfile, threading.Lock())
        with LOCK:
            SOCKETS.append(entry)
            online = {s[0] for s in SOCKETS}
        members = [{"user_id": u["id"], "status": presence_of(n) if u["id"] in online else "offline", "status_message": None, "user_voice": []} for n, u in USERS.items()]
        emit({"type": "presence_sync", "members": members}, only={me})
        emit({"type": "presence_update", "user_id": me, "status": presence_of(s["user"]), "status_message": None, "typing": None, "nickname": None})
        try:
            while True:
                head = self.rfile.read(2)
                if len(head) < 2:
                    break
                opcode, length = head[0] & 0x0F, head[1] & 0x7F
                if length == 126:
                    length = struct.unpack(">H", self.rfile.read(2))[0]
                elif length == 127:
                    length = struct.unpack(">Q", self.rfile.read(8))[0]
                mask = self.rfile.read(4)
                data = bytes(b ^ mask[i % 4] for i, b in enumerate(self.rfile.read(length)))
                if opcode == 8:
                    break
                if opcode != 1:
                    continue
                msg = json.loads(data)
                sys.stderr.write("WS %s %s\n" % (s["user"], msg.get("type")))
                if msg.get("type") == "heartbeat":
                    emit({"type": "heartbeat_ack"}, only={me})
                elif msg.get("type") == "typing":
                    emit({"type": "typing", "channel_id": msg["channel_id"], "user_id": me, "is_typing": True}, only=audience(msg["channel_id"]))
                elif msg.get("type") == "voice_join" and ACCESS["on"]:
                    self.voice_check(me, msg.get("channel_id"))
        except (OSError, ValueError):
            pass
        finally:
            with LOCK:
                if entry in SOCKETS:
                    SOCKETS.remove(entry)
                gone = me not in {s[0] for s in SOCKETS}
            if gone:
                emit({"type": "presence_update", "user_id": me, "status": "offline", "status_message": None, "typing": None, "nickname": None})
            self.close_connection = True

    def voice_check(self, me, channel_id):
        """`voice_join` como o servidor: sem `connect_voice` (ou fora de um canal de voz) responde `voice-forbidden`.
        Entrar de fato não existe neste fake: quem pode não recebe resposta nenhuma."""
        channel = next((c for c in CHANNELS if c["id"] == channel_id), None)
        if channel is None:
            return emit({"type": "error", "message": "canal não encontrado"}, only={me})
        if channel["type"] != "voice" or not has_channel_flag(channel_id, me, "connect_voice"):
            emit({"type": "error", "message": "sem permissão para a sala de voz", "code": "voice-forbidden"}, only={me})

    def chat_get(self, url, query):
        path = url.path
        if path == "/ws":
            self.websocket()
            return True
        if serve_profiles(self, path):
            return True
        m = re.fullmatch(r"/users/([^/]+)/avatar", path)
        if m:
            self.problem(404, "Não encontrado", "Sem avatar")
            return True
        m = re.fullmatch(r"/attachments/([^/]+)(/thumbnail)?", path)
        if m and session_of(self.cookie()) is None:
            self.problem(401, "Não autorizado", "Token ausente, inválido ou expirado")
            return True
        if m and m.group(1) in FILES:
            self.attachment(m.group(1), thumbnail=m.group(2) is not None)
            return True
        s = session_of(self.cookie())
        embed = re.fullmatch(r"/embeds/([^/]+)(/video)?", path)
        if embed and s is None:
            self.problem(401, "Não autorizado", "Token ausente, inválido ou expirado")
            return True
        if s is None:
            return False
        me = uid(s["user"])
        if embed:
            self.embed(embed.group(1), bool(embed.group(2)), me)
            return True
        if path == "/channels":
            out = []
            for c in CHANNELS:
                item = dict(c)
                item["last_message"] = last_message(c["id"])
                item["last_read_message"] = READS.get((me, c["id"]))
                item["notification_settings"] = "only_mentions"
                if ACCESS["on"]:
                    # Todos os canais que não são DM, sem filtrar leitura e com a última mensagem de canal que o usuário
                    # não lê: como o servidor, que deixa o app esconder. Cópia do defeito, não decisão.
                    item["permissions"] = entry_views(c["id"])
                out.append(item)
            self.send(200, {"channels": out})
        elif path == "/dms":
            self.send(200, {"dms": [dm_view(d, me) for d, pair in DMS.items() if me in pair]})
        elif path == "/users":
            self.send(200, {"users": [summary(n) for n in USERS], "has_more": False})
        elif path == "/roles" and ACCESS["on"]:
            self.send(200, {"roles": [role_view(r) for r in ROLES.values()]})
        elif re.fullmatch(r"/channels/[^/]+/permissions", path) and ACCESS["on"]:
            cid = path.split("/")[2]
            if cid in DMS or not any(c["id"] == cid for c in CHANNELS):
                self.problem(404, "Recurso não encontrado", "canal não encontrado")
            else:
                self.send(200, {"channel_id": cid, "permissions": entry_views(cid)})
        elif path == "/emojis":
            # Como o servidor real: 25 por página, cursor since + last_id e token bucket (RATE_LIMIT=10, RATE_BURST=20).
            tokens, at = EMOJI_BUCKETS.get(self.cookie(), [20.0, time.monotonic()])
            now_t = time.monotonic()
            tokens = min(20.0, tokens + (now_t - at) * 10)
            if tokens < 1:
                EMOJI_BUCKETS[self.cookie()] = [tokens, now_t]
                return self.problem(429, "Limite de requisições excedido", "muitas requisições, tente novamente mais tarde") or True
            EMOJI_BUCKETS[self.cookie()] = [tokens - 1, now_t]
            # FAKE_EMOJI_DELAY=1.0 simula um servidor lento (segundos por página), para medir a tela durante a carga.
            time.sleep(float(os.environ.get("FAKE_EMOJI_DELAY", "0")))
            rest = EMOJIS
            last_id = query.get("last_id", [None])[0]
            if last_id:
                ids = [e["id"] for e in EMOJIS]
                rest = EMOJIS[ids.index(last_id) + 1:] if last_id in ids else []
            self.send(200, {"emojis": rest[:25], "has_more": len(rest) > 25})
        elif re.fullmatch(r"/channels/[^/]+/pinned", path):
            cid = path.split("/")[2]
            if not has_channel_flag(cid, me, "read_channel"):
                self.forbidden("usuário não tem permissão para ler o canal")
                return True
            byid = {x["id"]: x for x in MESSAGES.get(cid, [])}
            self.send(200, {"channel_id": cid, "pinned": [view(byid[i], me) for i in PINS.get(cid, []) if i in byid]})
        elif re.fullmatch(r"/channels/[^/]+/messages/[^/]+/reactions", path):
            cid, mid = path.split("/")[2], path.split("/")[4]
            # A ordem do servidor: canal, mensagem do canal, depois a permissão de leitura.
            msg = self.find(mid)
            if not any(c["id"] == cid for c in CHANNELS) and cid not in DMS:
                self.problem(404, "Recurso não encontrado", "canal não encontrado")
            elif msg is None or msg["channel_id"] != cid:
                self.problem(404, "Recurso não encontrado", "mensagem não encontrada neste canal")
            elif not has_channel_flag(cid, me, "read_channel"):
                self.forbidden("usuário não tem permissão para ler o canal")
            else:
                body = reaction_page(msg, query)
                if body is None:
                    self.problem(400, "Parâmetro inválido", "order deve ser asc ou desc; since deve ser um timestamp ISO 8601")
                else:
                    self.send(200, body)
        elif re.fullmatch(r"/channels/[^/]+/messages", path):
            cid = path.split("/")[2]
            if not has_channel_flag(cid, me, "read_channel"):
                self.forbidden("usuário não tem permissão para ler o canal")
                return True
            asc = query.get("order", ["desc"])[0] == "asc"
            since, last_id = query.get("since", [None])[0], query.get("last_id", [None])[0]
            ms = sorted(MESSAGES.get(cid, []), key=lambda x: (x["created_at"], x["id"]))
            # Cursor (created_at, id), como no backend: o que vem depois dele (asc) ou antes dele (desc).
            if since and last_id:
                cursor = (since, last_id)
                ms = [x for x in ms if ((x["created_at"], x["id"]) > cursor if asc else (x["created_at"], x["id"]) < cursor)]
            if not asc:
                ms.reverse()
            limit = int(query.get("limit", ["30"])[0])
            self.send(200, {"channel_id": cid, "messages": [view(x, me) for x in ms[:limit]], "has_more": len(ms) > limit})
        else:
            return False
        return True

    def find(self, message_id):
        for cid, ms in MESSAGES.items():
            for m in ms:
                if m["id"] == message_id:
                    return m
        return None

    def chat_write(self, method, url, me):
        path = url.path
        if serve_profile_writes(self, method, path, me):
            return
        if method == "POST" and path == "/messages":
            ctype = self.headers.get("Content-Type", "")
            control = send_control()
            try:
                body = read_upload(self, control["rate"] or UPLOAD["kbps"])
                if body is None:
                    # O cliente desistiu no meio do envio: como um servidor de verdade, não cria mensagem de um corpo cortado.
                    self.close_connection = True
                    return
                time.sleep(control["delay"])
                fields, files = parse_upload(ctype, body)
                check_upload_form(files)
                cid = fields["channel_id"]
                # Em DM o anexo não pede `send_attachment`; em canal, sim (e o envio pede `send_messages`).
                if not has_channel_flag(cid, me, "send_messages") or (files and cid not in DMS and not has_role_flag(me, "send_attachment")):
                    return self.forbidden("usuário não tem permissão para enviar esta mensagem")
                check_upload_sizes(files)
            except UploadRejected as rejected:
                return self.problem(rejected.status, rejected.title, rejected.detail)
            attachments = []
            for name, data, mime in files:
                aid = str(uuid.uuid4())
                mime = sniffed_mime(mime, name)
                FILES[aid] = (mime, data)
                image = mime.startswith("image/")
                attachments.append({"id": aid, "mime_type": mime, "original_file_name": sanitized_file_name(name) or "arquivo", "size_bytes": len(data),
                                    "thumbnail_id": aid if image else None, "thumbnail_width": 96 if image else None,
                                    "thumbnail_height": 64 if image else None, "moderation_status": "clean"})
            if control["status"] >= 400 and not control["create"]:
                return self.problem(control["status"], "Erro simulado", "Resposta simulada por FAKE_SEND_STATUS")
            m = new_message(fields["channel_id"], me, fields.get("content", ""), fields.get("reply_to"), attachments)
            READS[(me, m["channel_id"])] = m["id"]
            event = {k: m[k] for k in ("id", "channel_id", "author_id", "content", "reply_to", "attachments", "created_at")}
            emit(dict(event, type="message"), only=audience(m["channel_id"]))
            crawl_links(m)
            if m["channel_id"] in DMS:
                for u in set(DMS[m["channel_id"]]):
                    emit({"type": "dm_update", "dm": dm_view(m["channel_id"], u)}, only={u})
            if control["status"] >= 400:
                return self.problem(control["status"], "Erro simulado", "Mensagem criada, mas a resposta foi simulada como erro")
            return self.send(201, view(m, me))
        data = self.body()
        if method == "POST" and path == "/search":
            found = search_messages(data, parse_qs(url.query), me)
            if found is None:
                return self.problem(400, "Parâmetro inválido", "pelo menos 1 filtro é obrigatório; since e last_id devem ser informados juntos")
            return self.send(200, found)
        if method == "POST" and path == "/dms":
            other = data["user_id"]
            dm_id = next((d for d, pair in DMS.items() if set(pair) == {me, other}), None)
            if dm_id is None:
                dm_id = str(uuid.uuid4())
                DMS[dm_id] = (me, other)
            return self.send(200, dm_view(dm_id, me))
        m = re.fullmatch(r"/messages/([^/]+)", path)
        if m:
            msg = self.find(m.group(1))
            if msg is None:
                return self.problem(404, "Não encontrado", "Mensagem inexistente")
            # Editar é só do autor; excluir a de outro pede `delete_messages` do canal (só com as permissões ligadas).
            if msg["author_id"] != me and not (method == "DELETE" and can_delete_other(msg["channel_id"], me)):
                return self.problem(403, "Proibido", "Só o autor pode alterar a mensagem")
            if method == "PUT":
                msg["content"], msg["edited_at"] = data["content"], stamp()
                emit({"type": "message_edit", "id": msg["id"], "channel_id": msg["channel_id"], "content": msg["content"], "edited_at": msg["edited_at"]}, only=audience(msg["channel_id"]))
                return self.send(200, view(msg, me))
            MESSAGES[msg["channel_id"]].remove(msg)
            emit({"type": "message_delete", "id": msg["id"], "channel_id": msg["channel_id"]}, only=audience(msg["channel_id"]))
            return self.send(204)
        m = re.fullmatch(r"/channels/([^/]+)/read", path)
        if m:
            READS[(me, m.group(1))] = data["message_id"]
            return self.send(204)
        m = re.fullmatch(r"/channels/([^/]+)/messages/([^/]+)/pin", path)
        if m:
            pinned = method == "POST"
            if not can_pin(m.group(1), me, unpin=not pinned):
                return self.forbidden("usuário não tem permissão para fixar a mensagem" if pinned
                                      else "usuário não tem permissão para remover a fixação da mensagem")
            pins = PINS.setdefault(m.group(1), [])
            if pinned and m.group(2) not in pins:
                pins.append(m.group(2))
            if not pinned and m.group(2) in pins:
                pins.remove(m.group(2))
            emit({"type": "message_pin", "message_id": m.group(2), "is_pinned": pinned})
            return self.send(200 if pinned else 204, {"channel_id": m.group(1), "message_id": m.group(2)} if pinned else None)
        m = re.fullmatch(r"/channels/([^/]+)/messages/([^/]+)/reactions", path)
        if m:
            msg = self.find(m.group(2))
            if method == "POST" and not has_channel_flag(msg["channel_id"], me, "send_messages"):
                return self.forbidden("usuário não tem permissão para reagir neste canal")
            if method != "POST" and not has_channel_flag(msg["channel_id"], me, "read_channel"):
                return self.forbidden("usuário não tem permissão para ler o canal")
            key = (data.get("emoji_id"), data.get("unicode"))
            users = msg["reactions"].setdefault(key, {})
            if method == "POST":
                react(msg, key, me)
            else:
                users.pop(me, None)
            emit({"type": "react_update", "message_id": msg["id"], "emoji_id": key[0], "unicode": key[1], "count": len(users)}, only=audience(msg["channel_id"]))
            return self.send(201 if method == "POST" else 204, {"message_id": msg["id"]} if method == "POST" else None)
        if ACCESS["on"] and self.access_write(method, path, data, me):
            return
        self.problem(404, "Não encontrado", "Rota inexistente")

    def access_write(self, method, path, data, me):
        """Cargos, cargos dos usuários e entradas de canal, como na OpenAPI. True se a rota é uma destas."""
        def allowed(flag):
            ok = has_role_flag(me, flag)
            if not ok:
                self.forbidden("usuário não possui a permissão necessária para esta operação")
            return ok

        if path == "/roles" and method == "POST":
            if allowed("manage_roles"):
                self.create_role(data)
            return True
        m = re.fullmatch(r"/roles/([^/]+)", path)
        if m and method in ("PUT", "DELETE"):
            if allowed("manage_roles"):
                self.change_role(method, m.group(1), data)
            return True
        m = re.fullmatch(r"/users/([^/]+)/roles", path)
        if m and method == "POST":
            if allowed("manage_roles"):
                self.assign_role(m.group(1), data.get("role_id"))
            return True
        m = re.fullmatch(r"/users/([^/]+)/roles/([^/]+)", path)
        if m and method == "DELETE":
            if allowed("manage_roles"):
                self.remove_role(m.group(1), m.group(2))
            return True
        m = re.fullmatch(r"/channels/([^/]+)/permissions/([^/]+)", path)
        if m and method == "PUT":
            if allowed("manage_channels"):
                self.set_entry(m.group(1), m.group(2), data.get("permissions"))
            return True
        m = re.fullmatch(r"/channels/([^/]+)/role/([^/]+)", path)
        if m and method == "DELETE":
            if allowed("manage_channels"):
                self.set_entry(m.group(1), m.group(2), None)
            return True
        return False

    def role_fields(self, data):
        """Nome, cor e flags de um corpo de criar ou atualizar cargo; None (depois de responder 400) se inválido."""
        name, color = (data.get("name") or "").strip(), data.get("color")
        if not name or len(name) > 32 or (color is not None and not re.fullmatch(r"#[0-9a-fA-F]{6}", str(color))):
            self.problem(400, "Parâmetro inválido", "name é obrigatório e deve ter no máximo 32 caracteres; color deve ser hexadecimal #RRGGBB")
            return None
        given = data.get("permissions") if isinstance(data.get("permissions"), dict) else {}
        return name, color, [f for f in ROLE_FLAGS if given.get(f) is True]

    def create_role(self, data):
        fields = self.role_fields(data)
        if fields is None:
            return
        name, color, flags = fields
        members = list(dict.fromkeys(data.get("users") or []))
        if any(r["name"] == name for r in ROLES.values()):
            return self.problem(409, "Nome de role já existe", "o nome informado já está em uso")
        if any(name_of(u) is None for u in members):
            return self.problem(404, "Recurso não encontrado", "usuário não encontrado")
        role = new_role(name, color, flags)
        for user_id in members:
            USER_ROLES.setdefault(user_id, []).append(role["id"])
            emit({"type": "role_add", "user_id": user_id, "role_id": role["id"]})
        self.send(201, role_view(role))

    def change_role(self, method, role_id, data):
        role = ROLES.get(role_id)
        if role is None:
            return self.problem(404, "Recurso não encontrado", "role não encontrada")
        # Como o servidor, nenhum dos dois manda evento: quem está conectado só vê a mudança ao reconectar.
        if method == "DELETE":
            del ROLES[role_id]
            for ids in USER_ROLES.values():
                if role_id in ids:
                    ids.remove(role_id)
            for entries in ENTRIES.values():
                entries.pop(role_id, None)
            return self.send(204)
        fields = self.role_fields(data)
        if fields is None:
            return
        name, color, flags = fields
        if any(r["name"] == name and r["id"] != role_id for r in ROLES.values()):
            return self.problem(409, "Nome de role já existe", "o nome informado já está em uso")
        role["name"], role["color"] = name, color
        role["permissions"] = {f: f in flags for f in ROLE_FLAGS}
        self.send(200, role_view(role))

    def assign_role(self, user_id, role_id):
        if not role_id:
            return self.problem(400, "Parâmetro inválido", "role_id é obrigatório")
        if name_of(user_id) is None:
            return self.problem(404, "Recurso não encontrado", "usuário não encontrado")
        if role_id not in ROLES:
            return self.problem(404, "Recurso não encontrado", "role não encontrada")
        # Atribuir de novo é idempotente, e o evento sai mesmo assim.
        if role_id not in USER_ROLES.setdefault(user_id, []):
            USER_ROLES[user_id].append(role_id)
        emit({"type": "role_add", "user_id": user_id, "role_id": role_id})
        self.send(201, {"user_id": user_id, "role_id": role_id, "assigned_at": now()})

    def remove_role(self, user_id, role_id):
        if name_of(user_id) is None:
            return self.problem(404, "Recurso não encontrado", "usuário não encontrado")
        if role_id not in ROLES:
            return self.problem(404, "Recurso não encontrado", "role não encontrada")
        if role_id not in USER_ROLES.get(user_id, []):
            return self.problem(404, "Recurso não encontrado", "role não atribuída ao usuário")
        USER_ROLES[user_id].remove(role_id)
        emit({"type": "role_remove", "user_id": user_id, "role_id": role_id})
        self.send(204)

    def set_entry(self, channel_id, role_id, permissions):
        """PUT (permissions é um objeto) ou DELETE (None) da entrada de um cargo no canal."""
        channel = next((c for c in CHANNELS if c["id"] == channel_id), None)
        if channel is None:
            return self.problem(404, "Recurso não encontrado", "canal não encontrado")
        if role_id not in ROLES:
            return self.problem(404, "Recurso não encontrado", "role não encontrada")
        if permissions is None:
            ENTRIES.get(channel_id, {}).pop(role_id, None)
        else:
            given = permissions if isinstance(permissions, dict) else {}
            ENTRIES.setdefault(channel_id, {})[role_id] = {f: given.get(f) is True for f in CHANNEL_FLAGS}
        # Cópia do defeito do servidor: este evento não leva as permissões e manda `parent_id` nulo, mesmo com o canal
        # numa categoria (a OpenAPI diz que ele vem sempre). Quem precisa das permissões relê `GET /channels`.
        emit({"type": "channel_update", "channel_id": channel_id, "name": channel["name"], "position": channel["position"],
              "topic": channel.get("topic"), "parent_id": None})
        if permissions is None:
            return self.send(204)
        self.send(200, {"channel_id": channel_id, "role_id": role_id, "permissions": dict(ENTRIES[channel_id][role_id])})

    def do_PUT(self):
        self.write("PUT")

    def do_DELETE(self):
        self.write("DELETE")

    def write(self, method):
        s = self.session()
        if s:
            self.chat_write(method, urlparse(self.path), uid(s["user"]))

    def do_GET(self):
        url = urlparse(self.path)
        if self.chat_get(url, parse_qs(url.query)):
            return
        if self.path == "/health":
            self.send_response(200)
            self.send_header("Content-Length", "2")
            self.end_headers()
            self.wfile.write(b"OK")
        elif self.path == "/server":
            server = {"id": str(uuid.uuid4()), "name": NAME, "public": SERVER_PASSWORD is None,
                      "owner_username": "dono", "member_count": len(USERS), "channel_count": 3}
            if ACCESS["on"]:
                server.update(owner_id=ACCESS["owner"], owner_username=name_of(ACCESS["owner"]), role_count=len(ROLES))
            self.send(200, server)
        elif self.path == "/auth/whoami":
            s = self.session()
            if s:
                self.send(200, {"id": USERS[s["user"]]["id"], "username": s["user"], "nickname": None,
                                "roles": role_summaries(uid(s["user"])) if ACCESS["on"] else [],
                                "settings": {"version": 1, "config": {}}})
        elif self.path == "/auth/connected_devices":
            s = self.session()
            if s:
                mine = [{"id": v["id"], "created_at": v["created"], "expires_at": v["created"]}
                        for v in SESSIONS.values() if v["user"] == s["user"]]
                self.send(200, {"connections": mine})
        else:
            self.problem(404, "Não encontrado", "Rota inexistente")

    def do_POST(self):
        if not self.path.startswith("/auth/"):
            return self.write("POST")
        data = self.body()
        if self.path == "/auth/login_server":
            if SERVER_PASSWORD is not None and data.get("server_password") == SERVER_PASSWORD:
                self.send(204, cookie="Auth=temp-token; Path=/; HttpOnly; Max-Age=1800; SameSite=Strict")
            else:
                self.problem(401, "Não autorizado", "Senha do servidor incorreta")
        elif self.path in ("/auth/login", "/auth/register"):
            if SERVER_PASSWORD is not None and self.cookie() != "temp-token" and session_of(self.cookie()) is None:
                return self.problem(401, "Não autorizado", "Servidor não público: valide a senha do servidor antes")
            username, password = data.get("username", ""), data.get("password", "")
            if self.path == "/auth/register":
                if username in USERS:
                    return self.problem(409, "Conflito", "Username já existe")
                if len(password) < 8 or password.lower() == password or password.isalnum():
                    return self.problem(400, "Parâmetro inválido", "A senha precisa de 8 caracteres, 1 maiúscula e 1 caractere especial")
                USERS[username] = {"id": str(uuid.uuid4()), "password": password, "created": now()}
                return self.send(201, {"id": USERS[username]["id"], "username": username, "created_at": now()})
            user = USERS.get(username)
            if user is None or user["password"] != password:
                return self.problem(401, "Não autorizado", "Usuário ou senha incorretos")
            token = open_session(username)
            self.send(200, {"user": {"id": user["id"], "username": username}, "connection_violation": False},
                      cookie=SESSION_COOKIE % token)
        elif self.path == "/auth/logout":
            SESSIONS.pop(self.cookie(), None)
            self.send(204, cookie="Auth=; Path=/; Max-Age=0")
        elif self.path == "/auth/refresh":
            renewed = refresh_session(self.cookie())
            if renewed is None:
                return self.problem(401, "Não autorizado", "Token ausente, inválido ou expirado")
            token, s = renewed
            self.send(200, {"connection": {"id": s["id"], "created_at": s["created"]}}, cookie=SESSION_COOKIE % token)
        elif self.path == "/auth/drop_connection":
            s = self.session()
            if s:
                target = data.get("connection_id")
                doomed = [t for t, v in SESSIONS.items() if v["user"] == s["user"] and (target == "ALL" or v["id"] == target)]
                for t in doomed:
                    del SESSIONS[t]
                self.send(200, {"dropped": len(doomed)})
        else:
            self.problem(404, "Não encontrado", "Rota inexistente")


# Limites do `POST /messages` no servidor de verdade (`handlers/messages.go`, `services/attachments.go`, `middleware/bodylimit.go`);
# a OpenAPI não os traz. O fake os aplica como ele, para o app mostrar o que o servidor diria.
UPLOAD = {"kbps": 0.0}          # FAKE_UPLOAD_KBPS
UPLOAD_BODY_LIMIT = 110 << 20   # MaxUploadBodySize: acima disso, 413 sem ler (Content-Length) ou 400 no meio da leitura (em pedaços)
UPLOAD_FILE_LIMIT = 100 * 1024 * 1024
UPLOAD_MAX_FILES = 10
UPLOAD_NAME_LIMIT = 128
NOT_MULTIPART = "corpo da requisição deve ser multipart/form-data válido"
# O 400 de entrada inválida do servidor lista todas as causas possíveis, sem dizer qual foi (ErrInvalidInput).
INVALID_INPUT = ("channel_id é obrigatório; content tem no máximo 8192 caracteres; a mensagem precisa de content ou attachment; "
                 "nome do attachment inválido; reply_to deve referenciar uma mensagem do mesmo canal")


class UploadRejected(Exception):
    """Um envio que o servidor de verdade recusaria com 400 ou 413, no formato de `problem()`."""

    def __init__(self, status, title, detail):
        super().__init__(detail)
        self.status, self.title, self.detail = status, title, detail


def read_upload(handler, kbps):
    """O corpo de `POST /messages`, lido como o servidor o faz: com `Content-Length` (acima de 110 MiB o 413 sai sem ler nada) ou em
    pedaços (`Transfer-Encoding: chunked`, o que o app manda quando algum arquivo não tem tamanho; acima de 110 MiB a leitura é
    cortada e o servidor responde 400). `kbps` (> 0) limita a leitura, para o envio de um arquivo grande ter o que mostrar.
    Devolve None se o cliente desistiu no meio. Quando recusa, deixa a conexão marcada para fechar: sobra corpo por ler."""

    def pace(count):
        if kbps > 0:
            time.sleep(count / (kbps * 1024))

    def refuse(status, title, detail):
        handler.close_connection = True
        raise UploadRejected(status, title, detail)

    if "chunked" in handler.headers.get("Transfer-Encoding", "").lower():
        body = bytearray()
        while True:
            line = handler.rfile.readline(65537)
            if not line:
                return None
            try:
                size = int(line.split(b";")[0].strip() or b"0", 16)
            except ValueError:
                refuse(400, "Parâmetro inválido", NOT_MULTIPART)
            if size == 0:
                while handler.rfile.readline(65537) not in (b"\r\n", b"\n", b""):  # os campos finais, se houver
                    pass
                return bytes(body)
            if len(body) + size > UPLOAD_BODY_LIMIT:
                refuse(400, "Parâmetro inválido", NOT_MULTIPART)
            remaining = size
            while remaining:
                piece = handler.rfile.read(min(16384, remaining))
                if not piece:
                    return None
                body += piece
                remaining -= len(piece)
                pace(len(piece))
            handler.rfile.readline()  # o CRLF depois do pedaço
    length = int(handler.headers.get("Content-Length") or 0)
    if length > UPLOAD_BODY_LIMIT:
        refuse(413, "Payload muito grande", "corpo da requisição excede o tamanho máximo permitido")
    body = bytearray()  # `bytes +=` copiaria o corpo inteiro a cada pedaço: com 100 MiB, minutos
    while len(body) < length:
        piece = handler.rfile.read(min(16384, length - len(body)))
        if not piece:
            return None
        body += piece
        pace(len(piece))
    return bytes(body)


def part_file_name(part):
    """O `filename` do cabeçalho da parte, em UTF-8. O analisador de e-mail lê o cabeçalho como ASCII e troca cada byte de um nome
    acentuado por um caractere à parte (ou por `?` em `get_filename`), então o nome é lido do cabeçalho cru."""
    for name, value in part.raw_items():
        found = re.search(r'filename="([^"]*)"', value) if name.lower() == "content-disposition" else None
        if found:
            return found.group(1).encode("utf-8", "surrogateescape").decode("utf-8", "replace")
    return part.get_filename() or ""


def parse_upload(content_type, body):
    """(campos, arquivos) de um corpo multipart: os campos como texto e cada arquivo como (nome, bytes, tipo declarado)."""
    parsed = BytesParser().parsebytes(b"Content-Type: " + content_type.encode() + b"\r\n\r\n" + body)
    if not parsed.is_multipart():
        raise UploadRejected(400, "Parâmetro inválido", NOT_MULTIPART)
    fields, files = {}, []
    for part in parsed.get_payload():
        name = part.get_param("name", header="content-disposition")
        data = part.get_payload(decode=True)
        if name == "attachments":
            files.append((part_file_name(part), data, part.get_content_type()))
        else:
            fields[name] = data.decode()
    if "channel_id" not in fields:
        raise UploadRejected(400, "Parâmetro inválido", INVALID_INPUT)
    return fields, files


def sanitized_file_name(name):
    """`SanitizeFileName` do servidor: sem pasta (`/` e `\\`), sem caracteres de controle e sem espaços nas pontas."""
    name = (name or "")[max((name or "").rfind("/"), (name or "").rfind("\\")) + 1:]
    return "".join(c for c in name if unicodedata.category(c) != "Cc").strip()


def check_upload_form(files):
    """O que o servidor confere antes de tudo: até 10 arquivos e, em cada um, um nome que sobra depois da limpeza, com até 128 caracteres."""
    if len(files) > UPLOAD_MAX_FILES:
        raise UploadRejected(400, "Parâmetro inválido", "máximo de 10 attachments por mensagem")
    for name, _, _ in files:
        cleaned = sanitized_file_name(name)
        if not cleaned or len(cleaned) > UPLOAD_NAME_LIMIT:
            raise UploadRejected(400, "Parâmetro inválido", INVALID_INPUT)


def check_upload_sizes(files):
    """O servidor só vê o tamanho de cada arquivo ao gravá-lo, depois de conferir a permissão: acima de 100 MiB é 400."""
    if any(len(data) > UPLOAD_FILE_LIMIT for _, data, _ in files):
        raise UploadRejected(400, "Parâmetro inválido", "attachment excede o tamanho máximo de 100MB")


def seed_track_a():
    """Sementes da trilha A do item 2 (galeria, envio, visualizador, áudio). Cada trilha escreve só na sua função, para os
    ramos mesclarem sem conflito; o `__main__` chama as duas depois do resto das sementes.

    Onda 1 (galeria e envio): não semeia mensagens, mas lê `FAKE_UPLOAD_KBPS=N`, que lê o corpo de `POST /messages` a N KB/s (o mesmo
    que `FAKE_SEND_RATE`, que tem prioridade quando os dois estão ligados): um vídeo grande mostra a barra de envio e dá tempo de
    cancelar no meio. O envio aceita corpo `Content-Length` e `chunked` e recusa como o servidor de verdade: 413 se o corpo declarado passa
    de 110 MiB, e 400 com mais de 10 arquivos, nome vazio ou com mais de 128 caracteres, arquivo acima de 100 MiB ou corpo em pedaços
    acima de 110 MiB. Valor ilegível derruba o fake na partida.

    Onda 2 (visualizador): o canal `galeria`, com mensagens de várias mídias (ver `seed_gallery`).

    Onda 3 (áudio): o canal `audio`, com áudios anexados (ver `seed_audio`); `FAKE_AUDIO_DIR` traz os arquivos de verdade."""
    try:
        UPLOAD["kbps"] = float(os.environ.get("FAKE_UPLOAD_KBPS") or 0)
    except ValueError as error:
        raise SystemExit("FAKE_UPLOAD_KBPS: valor ilegível (%s)" % error)
    seed_gallery()
    seed_audio(os.environ.get("FAKE_AUDIO_DIR"))


def audio_attachment(name, mime, data, moderation="clean"):
    """Registra o arquivo de um áudio e devolve o anexo como o servidor o descreve: sem miniatura e sem dimensões."""
    aid = str(uuid.uuid4())
    FILES[aid] = (mime, data)
    if moderation == "blocked":   # o servidor real responde 410 a um anexo bloqueado
        GONE.add(aid)
    return {"id": aid, "mime_type": mime, "original_file_name": name, "size_bytes": len(data), "width": None, "height": None,
            "thumbnail_id": None, "thumbnail_width": None, "thumbnail_height": None, "moderation_status": moderation}


def seed_audio(folder):
    """O canal `audio`, para o player de áudio. Sem `folder` (FAKE_AUDIO_DIR) só traz os dois casos que não precisam de arquivo: um
    áudio que o aparelho não decodifica e um que o servidor já bloqueou, embora a lista ainda o diga limpo. Com a pasta traz cada
    arquivo dela (o primeiro seguido de 30 mensagens curtas, para o cartão dele sair da tela), a gravação de voz do web, o envio
    antigo de tipo genérico, o áudio sensível e dois áudios numa mensagem só."""
    channel = {"id": "c0000000-0000-4000-8000-0000000000a2", "name": "audio", "type": "text", "position": 7,
               "topic": "Player de áudio", "parent_id": CHANNELS[0]["id"]}
    CHANNELS.append(channel)
    cid = channel["id"]
    root = Path(folder) if folder else None
    files = sorted(p for p in root.iterdir() if p.suffix.lower() in AUDIO_TYPES) if root is not None and root.is_dir() else []
    if folder and not files:
        raise SystemExit("FAKE_AUDIO_DIR: nenhum .ogg, .opus, .mp3, .m4a, .wav ou .webm em %s" % folder)
    loaded = [(path.name, path.suffix.lower(), AUDIO_TYPES[path.suffix.lower()], path.read_bytes()) for path in files]
    for index, (name, _, mime, data) in enumerate(loaded):
        new_message(cid, uid("bia"), "áudio %s" % name, attachments=[audio_attachment(name, mime, data)])
        if index == 0:
            for n in range(1, 31):
                new_message(cid, uid("caio"), "mensagem %d, só para o áudio de cima sair da tela" % n)
    if loaded:
        name, ext, mime, data = min(loaded, key=lambda f: (len(f[3]), len(f[0])))   # o menor: as variações não pesam
        new_message(cid, uid("caio"), "gravação de voz do web: o servidor a farejou como vídeo, e só o nome diz que é áudio",
                    attachments=[audio_attachment("audio_gravado" + ext, "video/webm", data)])
        new_message(cid, uid("bia"), "envio antigo, de tipo genérico: só a extensão diz que é áudio",
                    attachments=[audio_attachment("antigo" + ext, "application/octet-stream", data)])
        new_message(cid, uid("caio"), "áudio sensível: fica escondido até o toque",
                    attachments=[audio_attachment(name, mime, data, "sensitive")])
        if len(loaded) > 1:
            first, second = loaded[0], loaded[1]
            new_message(cid, uid("caio"), "dois áudios numa mensagem: só um toca por vez",
                        attachments=[audio_attachment(first[0], first[2], first[3]), audio_attachment(second[0], second[2], second[3])])
    new_message(cid, uid("bia"), "formato que o aparelho não decodifica (bytes que não são Ogg)",
                attachments=[audio_attachment("quebrado.ogg", "audio/ogg", b"isto nao e um arquivo ogg" * 40)])
    removed = audio_attachment("removido.ogg", "audio/ogg", b"OggS" + bytes(200))
    GONE.add(removed["id"])   # a lista ainda diz `clean`, mas o servidor já bloqueou: o arquivo responde 410
    new_message(cid, uid("caio"), "bloqueado pela moderação, mas a lista ainda não sabe (o arquivo responde 410)", attachments=[removed])


def seed_gallery():
    """O canal `galeria`, para o visualizador de mídia: quatro mídias de proporções e tipos diferentes numa mensagem, uma imagem
    só (sem contador), uma imagem de 4800x3200 (passa do lado de 4096 px em que o visualizador decodifica, para o zoom), sensíveis
    entre normais, uma bloqueada entre normais e, havendo vídeos semeados (`FAKE_VIDEO_DIR`), imagens, vídeo e GIF juntos.
    Cada imagem é desenhada com borda e cantos coloridos, para ver se o zoom ou o arrasto passam das bordas."""
    channel = {"id": "c0000000-0000-4000-8000-0000000000a1", "name": "galeria", "type": "text", "position": 6,
               "topic": "Visualizador de mídia", "parent_id": CHANNELS[0]["id"]}
    CHANNELS.append(channel)
    cid = channel["id"]
    gif = animated_gif()

    def shot(name, w, h, hue, moderation="clean"):
        return media_attachment(name, "image/png", frame_png(w, h, hue), w, h, moderation)

    wide, tall, square = (35, 120, 200), (200, 90, 35), (120, 60, 180)
    new_message(cid, uid("bia"), "quatro mídias: larga, alta, quadrada e um GIF",
                attachments=[shot("larga.png", 1200, 630, wide), shot("alta.png", 600, 900, tall), shot("quadrada.png", 512, 512, square),
                             media_attachment("danca.gif", "image/gif", gif, 320, 180)])
    new_message(cid, uid("caio"), "uma imagem só (sem contador)", attachments=[shot("unica.png", 800, 600, wide)])
    new_message(cid, uid("bia"), "imagem grande para o zoom (4800x3200)", attachments=[shot("grande.png", 4800, 3200, (30, 150, 110))])
    new_message(cid, uid("caio"), "sensíveis entre normais (só a que você toca entra no visualizador)",
                attachments=[shot("normal-1.png", 640, 480, wide), shot("sensivel-1.png", 640, 480, tall, "sensitive"),
                             shot("sensivel-2.png", 640, 480, square, "sensitive"), shot("normal-2.png", 640, 480, (30, 150, 110))])
    new_message(cid, uid("bia"), "uma bloqueada entre normais (o aviso fica no cartão e ela não entra no visualizador)",
                attachments=[shot("antes.png", 640, 480, wide), shot("bloqueada.png", 640, 480, tall, "blocked"), shot("depois.png", 640, 480, square)])
    if SEEDED:
        # O menor vídeo semeado: as quatro mídias juntas pesam pouco quando alguém toca o vídeo.
        name, data, mime, width, height = min(SEEDED, key=lambda f: (len(f[1]), len(f[0])))
        new_message(cid, uid("caio"), "imagem, vídeo, GIF e imagem juntos (precisa de FAKE_VIDEO_DIR)",
                    attachments=[shot("foto-1.png", 800, 600, wide), video_attachment(name, data, mime, width, height),
                                 media_attachment("danca.gif", "image/gif", gif, 320, 180), shot("foto-2.png", 600, 900, tall)])


def media_attachment(name, mime, data, width, height, moderation="clean"):
    """Registra o arquivo de uma imagem e devolve o anexo como o servidor o descreve: com miniatura (o fake serve o próprio
    arquivo como miniatura, então a de um GIF também é um GIF animado, como no servidor) e as dimensões dela."""
    aid = str(uuid.uuid4())
    FILES[aid] = (mime, data)
    if moderation == "blocked":   # o servidor real responde 410 a um anexo bloqueado
        GONE.add(aid)
    return {"id": aid, "mime_type": mime, "original_file_name": name, "size_bytes": len(data), "width": width, "height": height,
            "thumbnail_id": aid, "thumbnail_width": width, "thumbnail_height": height, "moderation_status": moderation}


def seed_embed(message, image_bytes=None, author_bytes=None, seed=None, **fields):
    """Põe um embed na mensagem, como o servidor o descreve (o que falta fica nulo, `fields` vazio). `image_bytes` e
    `author_bytes` são o que `GET /embeds/{id}` devolve em `image_data` e `author_image_data`; `seed` é o vídeo que o relay serve."""
    embed = {"id": str(uuid.uuid4()), "source_type": "link", "fetch_method": "opengraph", "provider": None, "site_name": None,
             "url": None, "title": None, "description": None, "color": None, "author": None, "thumbnail": None, "image": None,
             "video": None, "footer": None, "fields": [], "created_at": now(), "fetched_at": now(), **fields}
    EMBEDS[embed["id"]] = {"view": embed, "channel_id": message["channel_id"], "image": image_bytes, "author_image": author_bytes, "seed": seed}
    message["embeds"].append(embed)
    return embed


def moderate(message, attachment_id, status):
    """O que a moderação faz com um anexo: grava o estado na mensagem; `sensitive` sai num `attachment_moderation_update`
    (o servidor só avisa esse estado); `blocked` não avisa, só passa a responder 410 (o servidor real apaga a mensagem logo
    depois, mas há uma janela entre as duas escritas, que é a que o app precisa tratar)."""
    with LOCK:
        for attachment in message["attachments"]:
            if attachment["id"] == attachment_id:
                attachment["moderation_status"] = status
        if status == "blocked":
            GONE.add(attachment_id)
    if status == "sensitive":
        emit({"type": "attachment_moderation_update", "channel_id": message["channel_id"], "message_id": message["id"],
              "attachment_id": attachment_id, "status": status}, only=audience(message["channel_id"]))


def moderation_delay():
    """FAKE_MODERATION: desligado (vazio ou 0); `1` liga com 20 s; outro número liga com esse tanto de segundos."""
    try:
        value = float(os.environ.get("FAKE_MODERATION") or 0)
    except ValueError as error:
        raise SystemExit("FAKE_MODERATION: valor ilegível (%s)" % error)
    return 20.0 if value == 1 else value


CUSTOM_DESCRIPTION = (
    "Resumo da execução:\n\n- **Testes**: 1.284 passaram, nenhum falhou\n- **Lint**: sem avisos\n- **Cobertura**: 91,4%\n"
    "- **Pacote**: `papo-1.4.2.apk`\n\nTudo certo para a próxima etapa. Os detalhes estão no [log da execução]"
    "(https://painel.example.com/build/42/log), com a lista completa dos módulos."
)


def seed_track_b():
    """Sementes da trilha B do item 2: o canal `embeds`, com embed customizado completo (cor, autor com ícone, campos, rodapé),
    GIFs de embed e anexados, imagens de embed de todas as proporções, embeds do YouTube e anexos em cada estado de moderação.

    `FAKE_MODERATION=1` (ou N, em segundos; 1 liga com 20) acrescenta uma mensagem com uma imagem que, depois de N s, vira
    sensível (evento ao vivo) e, depois de mais N s, bloqueada (o arquivo passa a responder 410, sem evento)."""
    channel = {"id": "c0000000-0000-4000-8000-0000000000b1", "name": "embeds", "type": "text", "position": 5,
               "topic": "Embeds, GIFs e moderação", "parent_id": CHANNELS[0]["id"]}
    CHANNELS.append(channel)
    cid = channel["id"]
    robot = {"name": "Robô de Build"}

    # Embeds customizados (webhook). Os campos vêm fora de ordem, para a grade seguir `position`.
    build = new_message(cid, None, "Build 42 concluído.", external_author=robot)
    seed_embed(
        build, image_bytes=frame_png(800, 450, (46, 204, 113)), author_bytes=png(231, 76, 60, 64, 64),
        source_type="custom", fetch_method="manual", site_name="Painel da Casa", url="https://painel.example.com/build/42",
        title="Build 42 passou", description=CUSTOM_DESCRIPTION, color="#2ECC71",
        author={"name": "Robô de Build", "url": "https://painel.example.com/robo", "media": {"mime_type": "image/png", "width": 64, "height": 64}},
        image={"mime_type": "image/png", "width": 800, "height": 450},
        footer={"text": "Executado em 3 min · ramo principal"},
        fields=[{"position": 1, "name": "Duração", "value": "3 min", "inline": True},
                {"position": 0, "name": "Ramo", "value": "principal", "inline": True},
                {"position": 2, "name": "Testes", "value": "1.284", "inline": True},
                {"position": 3, "name": "Falhas", "value": "0", "inline": True},
                {"position": 4, "name": "Notas", "value": "Sem falhas. A cobertura subiu 0,3 ponto desde a build anterior.", "inline": False},
                {"position": 5, "name": "Quem enviou", "value": "equipe do painel", "inline": True}],
    )
    backup = new_message(cid, None, "Backup noturno.", external_author={"name": "Robô de Backup"})
    seed_embed(backup, source_type="custom", fetch_method="manual", title="Backup concluído", description="Copiados 12 GB em 4 min.",
               footer={"text": "Próximo backup às 02h"})
    dark = new_message(cid, None, "Alerta de disco.", external_author={"name": "Robô de Alertas"})
    seed_embed(dark, source_type="custom", fetch_method="manual", title="Disco quase cheio", description="Restam 3% no volume de dados.",
               color="#111111", fields=[{"position": 0, "name": "Volume", "value": "/dados", "inline": False}])
    hostile = new_message(cid, None, "Links que o app não abre (o servidor real os recusa na criação).", external_author={"name": "Robô de Teste"})
    seed_embed(hostile, source_type="custom", fetch_method="manual", url="javascript:alert(1)", title="Título com link inválido",
               author={"name": "Autor com link inválido", "url": "intent://exemplo.com#Intent;scheme=https;end"},
               description="Só texto; [link de arquivo](file:///sdcard/segredo.txt) e [link bom](https://exemplo.com/ok).")

    # Embeds de GIF (Tenor, GIPHY) e um GIF que chega como imagem de OpenGraph: os três animam.
    tenor = "https://tenor.com/view/gato-dancando-1234567890"
    gif = animated_gif()
    seed_embed(new_message(cid, uid("caio"), tenor), image_bytes=gif, fetch_method="gif", provider="Tenor", url=tenor, title="Gato dançando",
               thumbnail={"mime_type": "image/gif", "width": 320, "height": 180, "size_bytes": len(gif)})
    square = animated_gif(200, 200, 3)
    giphy = "https://giphy.com/gifs/quadrado-9876543210"
    seed_embed(new_message(cid, uid("bia"), giphy), image_bytes=square, fetch_method="gif", provider="GIPHY", url=giphy,
               thumbnail={"mime_type": "image/gif", "width": 200, "height": 200, "size_bytes": len(square)})
    seed_embed(new_message(cid, uid("bia"), "Página com GIF no OpenGraph"), image_bytes=gif, site_name="exemplo.com", url="https://exemplo.com/gif",
               title="GIF como og:image", description="Embed comum cuja imagem é um GIF animado.",
               thumbnail={"mime_type": "image/gif", "width": 320, "height": 180, "size_bytes": len(gif)})

    # Imagens de embed 1200x630, 600x900 e 512x512, sem e com vídeo (o vídeo é 16:9 e a caixa segue a imagem), e uma sem medidas.
    hues = {(1200, 630): (35, 120, 200), (600, 900): (200, 90, 35), (512, 512): (120, 60, 180)}
    for (width, height), hue in hues.items():
        for with_video in (False, True):
            link = "https://videos.example.com/%dx%d.mp4" % (width, height)
            found, mime = embed_seed(link)
            seed_embed(
                new_message(cid, uid("bia"), "imagem %dx%d %s vídeo" % (width, height, "com" if with_video else "sem")),
                image_bytes=frame_png(width, height, hue), seed=found if with_video else None, site_name="exemplo.com",
                url="https://exemplo.com/%dx%d" % (width, height), title="Imagem %dx%d" % (width, height),
                description="A imagem aparece inteira, sem corte nas laterais.",
                thumbnail={"mime_type": "image/png", "width": width, "height": height},
                video={"url": link, "mime_type": mime, "width": 1280, "height": 720} if with_video else None,
            )
    seed_embed(new_message(cid, uid("bia"), "imagem 1200x630 sem medidas no embed"), image_bytes=frame_png(1200, 630), site_name="exemplo.com",
               url="https://exemplo.com/sem-medidas", title="Sem largura nem altura", thumbnail={"mime_type": "image/png"})

    # Uma mensagem com um anexo e vários embeds, para o visualizador percorrer: o anexo e depois uma página por embed com imagem
    # (comum, custom só com `image`, GIF e custom com `thumbnail` e `image`, de que o servidor só serve a thumbnail). Ficam de
    # fora do visualizador o embed com vídeo (o cartão tem o gesto do vídeo) e o que não tem imagem.
    multi = new_message(cid, uid("bia"), "vários embeds com imagem e um anexo",
                        attachments=[media_attachment("anexo.png", "image/png", frame_png(640, 360, (90, 140, 60)), 640, 360)])
    for number, (width, height, hue) in enumerate([(1200, 630, (35, 120, 200)), (600, 900, (200, 90, 35))], start=1):
        seed_embed(multi, image_bytes=frame_png(width, height, hue), site_name="exemplo.com", url="https://exemplo.com/galeria/%d" % number,
                   title="Imagem %d da galeria" % number, thumbnail={"mime_type": "image/png", "width": width, "height": height})
    seed_embed(multi, image_bytes=frame_png(512, 512, (120, 60, 180)), source_type="custom", fetch_method="manual", title="Só com image",
               image={"mime_type": "image/png", "width": 512, "height": 512})
    seed_embed(multi, image_bytes=gif, fetch_method="gif", provider="Tenor", url=tenor, title="Gato dançando",
               thumbnail={"mime_type": "image/gif", "width": 320, "height": 180, "size_bytes": len(gif)})
    seed_embed(multi, image_bytes=frame_png(800, 450, (46, 204, 113)), source_type="custom", fetch_method="manual",
               title="Com thumbnail e image", description="O servidor serve só a thumbnail (verde); a image (600x600) não chega ao app.",
               thumbnail={"mime_type": "image/png", "width": 800, "height": 450}, image={"mime_type": "image/png", "width": 600, "height": 600})
    clip, clip_mime = embed_seed("https://videos.example.com/galeria.mp4")
    seed_embed(multi, image_bytes=frame_png(1200, 630, (200, 40, 60)), seed=clip, site_name="exemplo.com", url="https://exemplo.com/galeria/video",
               title="Embed com vídeo (fora do visualizador)", thumbnail={"mime_type": "image/png", "width": 1200, "height": 630},
               video={"url": "https://videos.example.com/galeria.mp4", "mime_type": clip_mime, "width": 1280, "height": 720})
    seed_embed(multi, source_type="custom", fetch_method="manual", title="Sem imagem (fora do visualizador)", description="Só texto.")

    # YouTube: o servidor entrega a página do player em `video.url`, sem `mime_type`, e a miniatura em `GET /embeds/{id}`. O id é
    # inventado (o player real responde "vídeo indisponível"); FAKE_YOUTUBE_ID põe um id de verdade para ver um vídeo tocar.
    youtube_id = os.environ.get("FAKE_YOUTUBE_ID", "aB3dE5gH7jK")
    if not re.fullmatch(r"[A-Za-z0-9_-]{11}", youtube_id):
        raise SystemExit("FAKE_YOUTUBE_ID: precisa ter 11 caracteres de A-Z, a-z, 0-9, _ e -")
    watch = "https://www.youtube.com/watch?v=" + youtube_id
    seed_embed(new_message(cid, uid("caio"), "Vídeo do YouTube: " + watch), image_bytes=frame_png(480, 270, (200, 40, 60)), fetch_method="oembed",
               provider="YouTube", site_name="YouTube", url=watch, title="Vídeo de exemplo",
               thumbnail={"mime_type": "image/png", "width": 480, "height": 270},
               video={"url": "https://www.youtube.com/embed/" + youtube_id})
    # Quase certo, mas não é o player: o host só começa com `youtube.com`. Não pode virar player; fica um cartão de link comum.
    lookalike = "https://youtube.com.evil.example.com/embed/" + youtube_id
    seed_embed(new_message(cid, uid("bia"), "Parece do YouTube, mas o endereço do player é de outro site"), image_bytes=frame_png(480, 270, (60, 60, 60)),
               fetch_method="oembed", provider="YouTube", site_name="YouTube", url="https://youtube.com.evil.example.com/watch?v=" + youtube_id,
               title="Embed com endereço falso", thumbnail={"mime_type": "image/png", "width": 480, "height": 270}, video={"url": lookalike})

    # Anexos: GIF animado (com selo), GIF sensível, pendente (aparece normal), bloqueado (aviso) e bloqueado no servidor antes do aviso.
    new_message(cid, uid("caio"), "GIF anexado (anima e tem o selo GIF)", attachments=[media_attachment("danca.gif", "image/gif", gif, 320, 180)])
    new_message(cid, uid("caio"), "GIF anexado sensível (escondido até o toque)",
                attachments=[media_attachment("danca-sensivel.gif", "image/gif", gif, 320, 180, "sensitive")])
    new_message(cid, uid("bia"), "imagem pendente (aparece normal: pending também é o servidor sem moderação)",
                attachments=[media_attachment("pendente.png", "image/png", frame_png(480, 320), 480, 320, "pending")])
    new_message(cid, uid("bia"), "imagem bloqueada (o aviso aparece e nada é pedido)",
                attachments=[media_attachment("bloqueada.png", "image/png", frame_png(480, 320), 480, 320, "blocked")])
    late = media_attachment("bloqueada-no-servidor.png", "image/png", frame_png(480, 320), 480, 320, "pending")
    new_message(cid, uid("bia"), "imagem que o servidor já bloqueou, mas a lista ainda diz pending (a miniatura responde 410)", attachments=[late])
    GONE.add(late["id"])

    delay = moderation_delay()
    if delay > 0:
        live = media_attachment("ao-vivo.png", "image/png", frame_png(480, 320, (35, 160, 120)), 480, 320, "pending")
        message = new_message(cid, uid("bia"), "moderação ao vivo: sensível em %g s e bloqueada em %g s" % (delay, 2 * delay), attachments=[live])
        for after, status in ((delay, "sensitive"), (2 * delay, "blocked")):
            timer = threading.Timer(after, moderate, (message, live["id"], status))
            timer.daemon = True
            timer.start()


if __name__ == "__main__":
    USERS["ana"] = {"id": str(uuid.uuid4()), "password": "Senha@123", "created": "2026-01-01T00:00:00Z"}
    USERS["bia"] = {"id": str(uuid.uuid4()), "password": "Senha@456", "created": "2026-01-02T00:00:00Z", "nickname": "Bia Borges",
                    "roles": [{"id": str(uuid.uuid4()), "name": "Admin", "color": "#E86CA5"}]}
    USERS["caio"] = {"id": str(uuid.uuid4()), "password": "Senha@789", "created": "2026-01-03T00:00:00Z"}
    seed_profiles()
    # Histórico longo e espalhado por vários dias: dá para paginar a conversa, a busca e os filtros de data.
    old = datetime(2026, 11, 10, 9, 0, 0, tzinfo=timezone.utc)
    for i in range(1, 261):
        when = (old + timedelta(hours=i)).isoformat().replace("+00:00", "Z")
        new_message(PAPO, uid("bia" if i % 3 else "caio"), "mensagem antiga número %d" % i, created_at=when)
    new_message(PAPO, None, "Deploy da versão 1.4 concluído em produção.", external_author={"name": "Robô de Deploy"})
    seed_webhooks()
    first = new_message(PAPO, uid("caio"), "Alguém viu o deploy de ontem? https://papo.example.com/status")
    aid = str(uuid.uuid4())
    FILES[aid] = ("image/png", png(255, 178, 56))
    new_message(PAPO, uid("bia"), "Vi sim, @mention(<@%s>) acompanhou comigo.\nSegue o print:" % uid("ana"), reply_to=first["id"],
                attachments=[{"id": aid, "mime_type": "image/png", "original_file_name": "print.png", "size_bytes": len(FILES[aid][1]),
                              "thumbnail_id": aid, "thumbnail_width": 96, "thumbnail_height": 64, "moderation_status": "clean"}])
    react(first, (None, "👍"), uid("bia"))
    new_message(CHANNELS[2]["id"], uid("bia"), "Manutenção no sábado às 10h.")
    # Uma conversa direta já existente: a busca geral não a alcança, só a busca com channel_id.
    dm = str(uuid.uuid4())
    DMS[dm] = (uid("ana"), uid("bia"))
    new_message(dm, uid("bia"), "Ana, o deploy de ontem passou nos testes?")
    new_message(dm, uid("ana"), "Passou! Só faltou atualizar a documentação.")

    # Emojis do servidor: 5 com nome de verdade e o resto para passar de duas páginas.
    palette = [(255, 99, 71), (60, 179, 113), (65, 105, 225), (218, 165, 32), (186, 85, 211)]
    # 200 emojis: 8 páginas, uns 8 s de carga no app (que espaça as páginas), para ver o carregamento.
    # Os dois animados (GIF e WebP) ocupam as vagas 6 e 7 e não mudam o total de 200.
    names = ["party_parrot", "ok_hand", "blob_wave", "cat_vibe", "check_ok", *ANIMATED_EMOJIS] + ["emoji_%03d" % i for i in range(8, 201)]
    # FAKE_ANIMATED_EMOJIS=N faz dos N primeiros `emoji_NNN` animados (GIF e WebP em alternância), para medir o seletor com
    # uma grade cheia de animações.
    many_animated = int(os.environ.get("FAKE_ANIMATED_EMOJIS", "0"))
    for i, name in enumerate(names):
        r, g, b = palette[i % len(palette)]
        if name in ANIMATED_EMOJIS:
            image_format, image_blob = ANIMATED_EMOJIS[name]
        elif 7 <= i < 7 + many_animated:
            image_format, image_blob = list(ANIMATED_EMOJIS.values())[i % 2]
        else:
            image_format = "PNG"
            # FAKE_EMOJI_SMALL=1 usa imagens minúsculas, para separar o custo dos dados do resto da carga.
            image_blob = base64.b64encode(png(r, g, b, 32, 32) if os.environ.get("FAKE_EMOJI_SMALL") else noisy_png((r + i * 7) % 256, g, b)).decode()
        EMOJIS.append({"id": str(uuid.uuid4()), "name": name, "format": image_format, "image_blob": image_blob,
                       "created_at": "2026-02-%02dT10:%02d:%02dZ" % (1 + i // 60, (i // 60) % 60, i % 60)})
    parrot, ok = EMOJIS[0]["id"], EMOJIS[1]["id"]
    new_message(PAPO, uid("bia"), "Chegou o emoji novo :party_parrot: e este também :ok_hand: mas :nao_existe: não")
    new_message(PAPO, uid("caio"), ":party_parrot:")
    new_message(PAPO, uid("bia"), "😀🎉👍")
    # Emojis animados (GIF e WebP): no meio do texto, e sozinhos, quando aparecem maiores.
    new_message(PAPO, uid("bia"), "Agora o emoji anima :party_gif: no meio do texto e também :wave_webp: de outro formato")
    new_message(PAPO, uid("caio"), ":party_gif:")
    new_message(PAPO, uid("caio"), "em código `:party_parrot:` não vira imagem, **negrito :ok_hand:** vira")
    reacted = new_message(PAPO, uid("bia"), "reaja com um emoji do servidor")
    react(reacted, (parrot, None), uid("bia"), "2026-11-24T12:30:00Z")
    react(reacted, (parrot, None), uid("caio"), "2026-11-24T12:30:01.5Z")
    react(reacted, (None, "🔥"), uid("caio"), "2026-11-24T12:30:02.25Z")
    if os.environ.get("FAKE_MANY_REACTORS"):
        # Três emojis intercalados: cada grupo continua na segunda página, e a ordem do cursor precisa de todos os grupos.
        crowd = new_message(PAPO, uid("bia"), "muita gente reagiu a esta mensagem")
        keys = [(None, "🔥"), (None, "❤"), (parrot, None)]
        for i in range(1, 131):
            name = "pessoa%03d" % i
            USERS[name] = {"id": str(uuid.uuid4()), "password": "Senha@555", "created": "2026-01-04T00:00:00Z", "nickname": "Pessoa %03d" % i}
            # Frações de 0, .25, .5 e .75 s: o Go corta os zeros à direita, então os textos têm tamanhos diferentes.
            moment = datetime(2026, 11, 24, 13, 0, 0, tzinfo=timezone.utc) + timedelta(seconds=i // 2, microseconds=(i % 4) * 250000)
            react(crowd, keys[i % 3], uid(name), nano(moment))
    video_settings()
    refresh_settings()
    RELAY["fail"] = os.environ.get("FAKE_RELAY_502", "") not in ("", "0")
    if os.environ.get("FAKE_VIDEO_DIR"):
        seed_videos(os.environ["FAKE_VIDEO_DIR"])
    if os.environ.get("FAKE_PERMISSIONS"):
        setup_access(permission_spec(os.environ["FAKE_PERMISSIONS"]))
        announce_access()
    seed_track_a()
    seed_track_b()
    print(f"{NAME} em http://0.0.0.0:{PORT}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
