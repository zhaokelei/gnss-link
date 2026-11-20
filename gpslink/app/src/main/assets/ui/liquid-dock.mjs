const clamp = (value, min, max) => Math.max(min, Math.min(max, value));

function spring(value, target, dt, frequency, damping) {
  // Small integration steps keep the spring stable on slower WebView frames.
  const count = Math.ceil(dt * 120);
  const step = dt / count;
  for (let i = 0; i < count; i++) {
    value.velocity += (frequency * frequency * (target - value.current)
      - 2 * damping * frequency * value.velocity) * step;
    value.current += value.velocity * step;
  }
  if (Math.abs(value.current - target) < 0.001 && Math.abs(value.velocity) < 0.01) {
    value.current = target;
    value.velocity = 0;
  }
}

export function attachLiquidDock(dock, switchTab) {
  const tabs = Array.from(dock.querySelectorAll(".dock-tab"));
  const lens = dock.querySelector(".dock-lens");
  const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)");
  const position = { current: 0, velocity: 0 };
  const scaleX = { current: 1, velocity: 0 };
  const scaleY = { current: 1, velocity: 0 };
  let selected = tabs.findIndex((tab) => tab.getAttribute("aria-selected") === "true");
  let centers = [];
  let width = 0;
  let pointer = null;
  let touchX = 0;
  let touchY = 0;
  let press = 0;
  let speed = 0;
  let frame = 0;
  let previousTime = 0;
  const instant = () => reducedMotion.matches || document.documentElement.hasAttribute("data-reduce-motion");

  function draw() {
    lens.style.transform = `translate3d(${(position.current - width / 2).toFixed(2)}px, 0, 0) scale(${scaleX.current.toFixed(4)}, ${scaleY.current.toFixed(4)})`;
    const highlightX = clamp(50 + (touchX - position.current) * 100 / (width * scaleX.current), 7, 93);
    lens.style.setProperty("--touch-x", `${highlightX.toFixed(1)}%`);
    lens.style.setProperty("--touch-y", `${touchY}%`);
    lens.style.setProperty("--lens-press", press.toFixed(3));
    window.LiquidGlassRefraction?.setExtraDisplacement(lens, press * (26 + speed * 5));
  }

  function snap() {
    cancelAnimationFrame(frame);
    frame = 0;
    previousTime = 0;
    position.current = centers[selected];
    position.velocity = scaleX.velocity = scaleY.velocity = 0;
    scaleX.current = scaleY.current = 1;
    press = speed = 0;
    draw();
  }

  function nearest(x) {
    return centers.reduce((best, center, index) =>
      Math.abs(center - x) < Math.abs(centers[best] - x) ? index : best, 0);
  }

  function tick(now) {
    frame = 0;
    const dt = previousTime ? clamp((now - previousTime) / 1000, 0.001, 0.042) : 1 / 60;
    previousTime = now;
    const target = pointer ? pointer.target : centers[selected];
    const pressTarget = pointer ? 1 : 0;
    press += (pressTarget - press) * (1 - Math.exp(-dt * (pointer ? 38 : 15)));
    if (Math.abs(press - pressTarget) < 0.001) press = pressTarget;
    const dragSpeed = pointer && now - pointer.lastMove < 45 ? Math.abs(pointer.velocity) : 0;
    speed += (clamp(dragSpeed / (width * 10), 0, 1) - speed) * (1 - Math.exp(-dt * 19));
    if (!dragSpeed && speed < 0.001) speed = 0;
    spring(position, target, dt, pointer ? 45 : 28, pointer ? 0.94 : 0.74);
    spring(scaleX, 1 + press * (0.18 + 0.14 * speed), dt, 38, 0.72);
    spring(scaleY, 1 + press * (0.30 - 0.16 * speed), dt, 40, 0.72);
    if (pointer) {
      const over = nearest(position.current);
      tabs.forEach((tab, index) => tab.classList.toggle("is-over", index === over));
    }
    if (!pointer && press === 0 && position.current === target && scaleX.current === 1 && scaleY.current === 1) {
      snap();
    } else {
      draw();
      if (!pointer || press < 0.999 || speed > 0.001 || position.current !== target ||
          scaleX.velocity || scaleY.velocity) {
        frame = requestAnimationFrame(tick);
      } else {
        previousTime = 0;
      }
    }
  }

  function animate() {
    if (!width) return;
    if (instant()) snap();
    else if (!frame) frame = requestAnimationFrame(tick);
  }

  function select(index) {
    if (index !== selected) {
      selected = index;
      tabs.forEach((tab, i) => {
        tab.setAttribute("aria-selected", String(i === index));
        tab.tabIndex = i === index ? 0 : -1;
      });
      switchTab(tabs[index].dataset.tab);
    }
    animate();
  }

  function measure() {
    const nextWidth = tabs[0].offsetWidth;
    if (!nextWidth) return;
    const nextCenters = tabs.map((tab) => tab.offsetLeft + tab.offsetWidth / 2);
    if (width === nextWidth && nextCenters.every((center, index) => center === centers[index])) return;
    if (pointer) finish(false);
    width = nextWidth;
    centers = nextCenters;
    lens.style.width = `${width}px`;
    dock.classList.add("has-liquid-selection");
    snap();
  }

  function track(event) {
    const delta = event.clientX - pointer.startX;
    pointer.moved ||= Math.abs(delta) > 5;
    pointer.target = clamp(centers[pointer.index] + delta, centers[0], centers.at(-1));
    touchX = event.clientX - pointer.left;
    touchY = clamp((event.clientY - pointer.top) * 100 / pointer.height, 0, 100);
    const now = performance.now();
    if (event.clientX !== pointer.lastX) {
      const dt = clamp((now - pointer.lastMove) / 1000, 0.008, 0.064);
      pointer.velocity = (event.clientX - pointer.lastX) / dt;
      pointer.lastMove = now;
      pointer.lastX = event.clientX;
    }
  }

  function finish(commit) {
    if (!pointer) return;
    const { id, index, moved, target } = pointer;
    pointer = null;
    if (dock.hasPointerCapture(id)) dock.releasePointerCapture(id);
    dock.classList.remove("is-pressed");
    tabs.forEach((tab) => tab.classList.remove("is-over"));
    if (commit) select(moved ? nearest(target) : index);
    else animate();
  }

  dock.addEventListener("pointerdown", (event) => {
    const index = tabs.indexOf(event.target.closest(".dock-tab"));
    if (index < 0 || pointer || !event.isPrimary || event.button !== 0 || !width) return;
    const { left } = dock.getBoundingClientRect();
    const { top, height } = tabs[index].getBoundingClientRect();
    pointer = {
      id: event.pointerId, index, left, top, height, startX: event.clientX,
      lastX: event.clientX, lastMove: performance.now(), velocity: 0, moved: false, target: centers[index],
    };
    dock.setPointerCapture(event.pointerId);
    track(event);
    if (!instant()) {
      // Initial outward droplet squeeze impulse under finger press.
      scaleX.velocity += (1 - press) * 5;
      scaleY.velocity += (1 - press) * 7;
      press += (1 - press) * 0.32;
      dock.classList.add("is-pressed");
      tabs[nearest(position.current)].classList.add("is-over");
      draw();
    }
    animate();
  });
  dock.addEventListener("pointermove", (event) => {
    if (pointer?.id !== event.pointerId) return;
    track(event);
    animate();
  });
  dock.addEventListener("pointerup", (event) => {
    if (pointer?.id !== event.pointerId) return;
    track(event);
    finish(true);
  });
  for (const type of ["pointercancel", "lostpointercapture"]) {
    dock.addEventListener(type, (event) => {
      if (pointer?.id === event.pointerId) finish(false);
    });
  }
  // A captured pointer ends on the dock. Keyboard and accessibility clicks
  // still reach the real buttons and use the same selection handler.
  tabs.forEach((tab, index) => tab.addEventListener("click", (event) => {
    if (event.detail === 0) select(index);
  }));
  dock.addEventListener("keydown", (event) => {
    const target = {
      ArrowLeft: (selected + tabs.length - 1) % tabs.length,
      ArrowRight: (selected + 1) % tabs.length,
      Home: 0,
      End: tabs.length - 1,
    }[event.key];
    if (target === undefined) return;
    event.preventDefault();
    select(target);
    tabs[target].focus();
  });
  window.addEventListener("blur", () => finish(false));
  const motionChanged = () => {
    if (!instant() || !width) return;
    finish(false);
    snap();
  };
  reducedMotion.addEventListener("change", motionChanged);
  new MutationObserver(motionChanged).observe(document.documentElement, {
    attributes: true, attributeFilter: ["data-reduce-motion"],
  });
  new ResizeObserver(measure).observe(dock);
  measure();
}

export function attachButtonHighlights() {
  const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)");
  const instant = () => reducedMotion.matches || document.documentElement.hasAttribute("data-reduce-motion");
  const states = new Map();
  let activeButton = null;
  let activePointer = null;
  let frame = 0;
  let previousTime = 0;

  function applyState(button, state) {
    button.style.setProperty("--btn-press", state.press.toFixed(3));
    button.style.setProperty("--btn-sx", state.scaleX.current.toFixed(4));
    button.style.setProperty("--btn-sy", state.scaleY.current.toFixed(4));
    button.style.setProperty("--btn-tx", `${state.shiftX.current.toFixed(2)}px`);
    button.style.setProperty("--btn-ty", `${state.shiftY.current.toFixed(2)}px`);
    if (!button.classList.contains("ios-switch")) {
      window.LiquidGlassRefraction?.setExtraDisplacement(button, state.press * (24 + state.speed * 4));
    }
  }

  function resetButton(button) {
    button.removeAttribute("data-touch-pressed");
    for (const prop of ["--btn-press", "--btn-sx", "--btn-sy", "--btn-tx", "--btn-ty"]) {
      button.style.removeProperty(prop);
    }
    if (!button.classList.contains("ios-switch")) {
      window.LiquidGlassRefraction?.setExtraDisplacement(button, 0);
    }
  }

  function updatePointer(button, state, event) {
    const bx = clamp((event.clientX - state.left) / Math.max(1, state.width), 0.06, 0.94) * 100;
    const by = clamp((event.clientY - state.top) / Math.max(1, state.height), 0.08, 0.92) * 100;
    button.style.setProperty("--button-x", `${bx.toFixed(1)}%`);
    button.style.setProperty("--button-y", `${by.toFixed(1)}%`);
    state.targetShiftX = clamp((event.clientX - state.startX) * 0.16, -7, 7);
    state.targetShiftY = clamp((event.clientY - state.startY) * 0.16, -4.5, 4.5);
    const now = performance.now();
    if (event.clientX !== state.lastX || event.clientY !== state.lastY) {
      const dt = clamp((now - state.lastMove) / 1000, 0.008, 0.064);
      state.velocity = (event.clientX - state.lastX) / dt;
      state.lastMove = now;
      state.lastX = event.clientX;
      state.lastY = event.clientY;
    }
  }

  function tick(now) {
    frame = 0;
    const dt = previousTime ? clamp((now - previousTime) / 1000, 0.001, 0.042) : 1 / 60;
    previousTime = now;
    for (const [button, state] of states) {
      if (instant() || !button.isConnected) {
        resetButton(button);
        states.delete(button);
        continue;
      }
      const pressTarget = state.pressed ? 1 : 0;
      state.press += (pressTarget - state.press) * (1 - Math.exp(-dt * (state.pressed ? 38 : 16)));
      if (Math.abs(state.press - pressTarget) < 0.001) state.press = pressTarget;

      const dragSpeed = state.pressed && now - state.lastMove < 48 ? Math.abs(state.velocity) : 0;
      state.speed += (clamp(dragSpeed / (Math.max(56, state.width) * 9), 0, 1) - state.speed) * (1 - Math.exp(-dt * 18));
      if (!dragSpeed && state.speed < 0.001) state.speed = 0;

      const isSwitch = button.classList.contains("ios-switch");
      const expandX = isSwitch ? 4.5 : clamp(state.width * 0.18, 11, 22);
      const expandY = isSwitch ? 6.5 : clamp(state.height * 0.30, 12, 16);
      const tx = 1 + state.press * (expandX / Math.max(32, state.width) + 0.09 * state.speed);
      const ty = 1 + state.press * (expandY / Math.max(28, state.height) - 0.10 * state.speed);

      spring(state.scaleX, tx, dt, state.pressed ? 40 : 34, state.pressed ? 0.72 : 0.64);
      spring(state.scaleY, ty, dt, state.pressed ? 42 : 36, state.pressed ? 0.72 : 0.64);
      spring(state.shiftX, state.pressed ? state.targetShiftX : 0, dt, 38, 0.76);
      spring(state.shiftY, state.pressed ? state.targetShiftY : 0, dt, 38, 0.76);

      if (!state.pressed && state.press === 0 &&
          state.scaleX.current === 1 && state.scaleY.current === 1 &&
          state.shiftX.current === 0 && state.shiftY.current === 0) {
        resetButton(button);
        states.delete(button);
      } else {
        applyState(button, state);
      }
    }
    if (states.size) {
      frame = requestAnimationFrame(tick);
    } else {
      previousTime = 0;
    }
  }

  function schedule() {
    if (instant()) {
      for (const [button] of states) resetButton(button);
      states.clear();
      return;
    }
    if (!frame && states.size) frame = requestAnimationFrame(tick);
  }

  function release() {
    if (activeButton) {
      const state = states.get(activeButton);
      if (state) {
        state.pressed = false;
        state.velocity = 0;
      }
    }
    activeButton = activePointer = null;
    schedule();
  }

  document.addEventListener("pointerdown", (event) => {
    const button = event.target.closest("button:not(.dock-tab):not(:disabled)") ||
      event.target.closest(".list-row.clickable")?.querySelector("button.row-btn:not(:disabled)");
    if (!button || !event.isPrimary || event.button !== 0) return;
    if (activeButton && activeButton !== button) release();
    const rect = button.getBoundingClientRect();
    const w = button.offsetWidth || rect.width || 64;
    const h = button.offsetHeight || rect.height || 44;
    let state = states.get(button);
    if (!state) {
      state = {
        press: 0,
        scaleX: { current: 1, velocity: 0 },
        scaleY: { current: 1, velocity: 0 },
        shiftX: { current: 0, velocity: 0 },
        shiftY: { current: 0, velocity: 0 },
        targetShiftX: 0,
        targetShiftY: 0,
        speed: 0,
        pressed: true,
        width: w,
        height: h,
        left: rect.left,
        top: rect.top,
        startX: event.clientX,
        startY: event.clientY,
        lastX: event.clientX,
        lastY: event.clientY,
        lastMove: performance.now(),
        velocity: 0,
      };
      states.set(button, state);
    } else {
      state.pressed = true;
      state.width = w;
      state.height = h;
      state.left = rect.left;
      state.top = rect.top;
      state.startX = state.lastX = event.clientX;
      state.startY = state.lastY = event.clientY;
      state.lastMove = performance.now();
      state.velocity = 0;
    }
    activeButton = button;
    activePointer = event.pointerId;
    updatePointer(button, state, event);
    button.setAttribute("data-touch-pressed", "");
    if (!instant()) {
      const isSwitch = button.classList.contains("ios-switch");
      state.scaleX.velocity += (1 - state.press) * (isSwitch ? 2.5 : 4.8);
      state.scaleY.velocity += (1 - state.press) * (isSwitch ? 3.2 : 6.8);
      state.press = Math.min(1, state.press + (1 - state.press) * 0.35);
      applyState(button, state);
    }
    schedule();
  }, { passive: true });

  document.addEventListener("pointermove", (event) => {
    if (event.pointerId !== activePointer || !activeButton) return;
    const state = states.get(activeButton);
    if (!state) return;
    updatePointer(activeButton, state, event);
    schedule();
  }, { passive: true });

  for (const type of ["pointerup", "pointercancel"]) {
    document.addEventListener(type, (event) => {
      if (event.pointerId === activePointer) release();
    }, { passive: true });
  }
  document.addEventListener("pointerout", (event) => {
    if (event.pointerId === activePointer && activeButton && !activeButton.contains(event.relatedTarget)) release();
  }, { passive: true });
  window.addEventListener("blur", release);
}
