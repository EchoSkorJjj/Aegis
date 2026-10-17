"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Bell } from "lucide-react";
import { toast } from "sonner";

import { formatDateTime } from "@/components/app/responses/response-status";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { ApiError, notificationApi } from "@/lib/api";
import type { OwnerNotification } from "@/lib/types";
import { useAuth } from "@/providers/auth-provider";

/** How often the unread count is checked while the app is open; the job itself runs every few minutes. */
const POLL_MS = 60_000;
const SHOWN = 10;

/**
 * The bell in the top bar (E9 story 5): items in the services you own that need your decision, newest first,
 * with how many you haven't read. Opening one marks it read and takes you to the item. Each item is also emailed
 * to you once. Only for service owners: nobody else is ever assigned a service, so nobody else is notified.
 */
export function NotificationBell() {
  const { user, isLoading } = useAuth();
  const enabled = !isLoading && (user?.roles ?? []).includes("SERVICE_OWNER");
  const router = useRouter();
  const [unread, setUnread] = useState(0);
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<OwnerNotification[] | null>(null);
  const [failed, setFailed] = useState(false);

  const refreshCount = useCallback(() => {
    notificationApi
      .unreadCount()
      .then((count) => setUnread(count.unread))
      .catch(() => {
        // The count is a nicety; the bell stays usable without it.
      });
  }, []);

  useEffect(() => {
    if (!enabled) return;
    refreshCount();
    const timer = window.setInterval(refreshCount, POLL_MS);
    return () => window.clearInterval(timer);
  }, [enabled, user?.orgId, refreshCount]);

  function toggle(next: boolean) {
    setOpen(next);
    if (!next) return;
    setFailed(false);
    notificationApi
      .list({ size: SHOWN })
      .then((page) => setItems(page.content))
      .catch(() => setFailed(true));
    refreshCount();
  }

  async function openItem(notification: OwnerNotification) {
    setOpen(false);
    if (!notification.readAt) {
      setUnread((count) => Math.max(0, count - 1));
      notificationApi.markRead(notification.id).catch(() => refreshCount());
    }
    router.push(`/findings/${notification.changeSignalId}`);
  }

  async function markAllRead() {
    try {
      await notificationApi.markAllRead();
      setUnread(0);
      setItems((current) => current?.map((item) => ({ ...item, readAt: item.readAt ?? new Date().toISOString() })) ?? null);
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not mark your notifications read.");
    }
  }

  if (!enabled) return null;

  const label = unread > 0 ? `Notifications, ${unread} unread` : "Notifications";
  return (
    <Popover open={open} onOpenChange={toggle}>
      <PopoverTrigger render={<Button variant="ghost" size="icon" aria-label={label} className="relative" />}>
        <Bell />
        {unread > 0 && (
          <span
            aria-hidden
            className="absolute -top-0.5 -right-0.5 flex min-w-4 items-center justify-center rounded-full bg-red-600 px-1 text-[10px] leading-4 font-medium text-white"
          >
            {unread > 99 ? "99+" : unread}
          </span>
        )}
      </PopoverTrigger>
      <PopoverContent align="end" className="w-96 p-0">
        <section aria-label="Notifications" className="text-sm">
          <div className="flex items-center justify-between gap-2 border-b px-3 py-2">
            <h2 className="font-medium">Needs your decision</h2>
            {unread > 0 && (
              <Button variant="ghost" size="sm" onClick={markAllRead}>
                Mark all read
              </Button>
            )}
          </div>
          {failed ? (
            <p className="px-3 py-6 text-center text-muted-foreground">Could not load your notifications.</p>
          ) : items === null ? (
            <p className="px-3 py-6 text-center text-muted-foreground">Loading…</p>
          ) : items.length === 0 ? (
            <p className="px-3 py-6 text-center text-muted-foreground">
              Nothing yet. When an item in a service you own needs your decision, it appears here and you get an
              email.
            </p>
          ) : (
            <ul className="max-h-96 divide-y overflow-y-auto">
              {items.map((item) => (
                <li key={item.id}>
                  <button
                    type="button"
                    onClick={() => openItem(item)}
                    className="flex w-full items-start gap-2 px-3 py-2 text-left hover:bg-muted/60 focus-visible:bg-muted/60 focus-visible:outline-none"
                  >
                    <span
                      aria-label={item.readAt ? undefined : "Unread"}
                      className={`mt-1.5 size-2 shrink-0 rounded-full ${item.readAt ? "bg-transparent" : "bg-sky-500"}`}
                    />
                    <span className="min-w-0">
                      <span className="block font-medium break-words">{item.title}</span>
                      <span className="block text-xs text-muted-foreground">
                        {item.itemKind === "VENDOR_OUTAGE" ? "Vendor outage" : "Vulnerability"} in {item.serviceName} ·
                        impact {item.score.toFixed(1)} · {formatDateTime(item.createdAt)}
                      </span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>
      </PopoverContent>
    </Popover>
  );
}
