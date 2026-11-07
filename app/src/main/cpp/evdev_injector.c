#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <linux/input.h>

static int fd = -1;
static int slot_active[16] = {0};

static inline void emit(int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    write(fd, &ev, sizeof(ev));
}

static inline void syn(void) {
    emit(EV_SYN, SYN_REPORT, 0);
}

static void touch_down(int slot, int x, int y) {
    if (slot < 0 || slot >= 16) return;
    emit(EV_ABS, ABS_MT_SLOT, slot);
    emit(EV_ABS, ABS_MT_TRACKING_ID, 1000 + slot);
    emit(EV_ABS, ABS_MT_POSITION_X, x);
    emit(EV_ABS, ABS_MT_POSITION_Y, y);
    emit(EV_ABS, ABS_MT_TOUCH_MAJOR, 50);
    emit(EV_KEY, BTN_TOUCH, 1);
    syn();
    slot_active[slot] = 1;
}

static void touch_move(int slot, int x, int y) {
    if (slot < 0 || slot >= 16) return;
    emit(EV_ABS, ABS_MT_SLOT, slot);
    emit(EV_ABS, ABS_MT_POSITION_X, x);
    emit(EV_ABS, ABS_MT_POSITION_Y, y);
    syn();
}

static void touch_up(int slot) {
    if (slot < 0 || slot >= 16) return;
    emit(EV_ABS, ABS_MT_SLOT, slot);
    emit(EV_ABS, ABS_MT_TRACKING_ID, -1);
    slot_active[slot] = 0;

    int any_active = 0;
    for (int i = 0; i < 16; i++) {
        if (slot_active[i]) {
            any_active = 1;
            break;
        }
    }
    if (!any_active) {
        emit(EV_KEY, BTN_TOUCH, 0);
    }
    syn();
}

static void cancel_all(void) {
    for (int i = 0; i < 16; i++) {
        if (slot_active[i]) {
            emit(EV_ABS, ABS_MT_SLOT, i);
            emit(EV_ABS, ABS_MT_TRACKING_ID, -1);
            slot_active[i] = 0;
        }
    }
    emit(EV_KEY, BTN_TOUCH, 0);
    syn();
}

int main(int argc, char** argv) {
    const char* dev = (argc > 1) ? argv[1] : "/dev/input/event4";
    fd = open(dev, O_RDWR);
    if (fd < 0) {
        fprintf(stderr, "Failed to open %s\n", dev);
        return 1;
    }

    setvbuf(stdin, NULL, _IONBF, 0);
    setvbuf(stdout, NULL, _IONBF, 0);
    printf("READY\n");

    char line[128];
    while (fgets(line, sizeof(line), stdin)) {
        char cmd = line[0];
        if (cmd == 'D') {
            int slot = 0, x = 0, y = 0;
            if (sscanf(line + 1, "%d %d %d", &slot, &x, &y) == 3) {
                touch_down(slot, x, y);
            }
        } else if (cmd == 'M') {
            int slot = 0, x = 0, y = 0;
            if (sscanf(line + 1, "%d %d %d", &slot, &x, &y) == 3) {
                touch_move(slot, x, y);
            }
        } else if (cmd == 'U') {
            int slot = 0;
            if (sscanf(line + 1, "%d", &slot) == 1) {
                touch_up(slot);
            }
        } else if (cmd == 'C') {
            cancel_all();
        } else if (cmd == 'Q') {
            cancel_all();
            break;
        }
    }

    close(fd);
    return 0;
}
