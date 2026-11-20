export const devicesFixture = [
  {
    id: "dev-android-1",
    name: "سمير موبايل",
    phone: "+201012345678",
    model: "Pixel 6a",
    platform: "android",
    sdk: 34,
    state: "online",
    lastSeen: "2026-11-20T10:58:00Z",
  },
  {
    id: "dev-android-2",
    name: "موبايل المكتب",
    phone: "+201098765432",
    model: "Galaxy A14",
    platform: "android",
    sdk: 33,
    state: "offline",
    lastSeen: "2026-11-19T18:20:00Z",
  },
  {
    id: "dev-windows-1",
    name: "لابتوب الوكيل",
    phone: "+201234567890",
    model: "ThinkPad T480",
    platform: "windows",
    sdk: 10,
    state: "syncing",
    lastSeen: "2026-11-20T11:02:00Z",
  },
];

export const usersFixture = [
  {
    email: "ops@wormgitty.local",
    password: "w0rm-gitty-pass",
    displayName: "مراقب الأجهزة",
  },
];
