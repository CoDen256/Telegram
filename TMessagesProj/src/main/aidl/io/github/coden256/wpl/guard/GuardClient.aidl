// GuardClient.aidl
package io.github.coden256.wpl.guard;

import io.github.coden256.wpl.guard.Ruling;

interface GuardClient {
    void onRulings(in List<Ruling> newRulings);
}