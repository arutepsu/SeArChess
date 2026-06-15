import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
} from "react";
import "./MenuCarousel.css";

export type MenuCarouselItem = {
  id: string;
  label: string;
  description?: string;
  badge?: string;
  disabled?: boolean;
  disabledReason?: string;
  onSelect?: () => void;
};

export type MenuCarouselProps = {
  title?: string;
  subtitle?: string;
  items: MenuCarouselItem[];
  initialSelectedId?: string;
  descriptionMode?: "all" | "selected";
  onBack?: () => void;
  className?: string;
};

function findEnabledIndex(items: MenuCarouselItem[], startIndex: number, step: 1 | -1) {
  if (items.length === 0) return -1;

  let index = startIndex;
  for (let checked = 0; checked < items.length; checked += 1) {
    index = (index + step + items.length) % items.length;
    if (!items[index]?.disabled) return index;
  }

  return -1;
}

function getInitialSelectedIndex(items: MenuCarouselItem[], initialSelectedId?: string) {
  const requestedIndex = initialSelectedId
    ? items.findIndex((item) => item.id === initialSelectedId && !item.disabled)
    : -1;

  if (requestedIndex >= 0) return requestedIndex;
  return items.findIndex((item) => !item.disabled);
}

export default function MenuCarousel({
  title,
  subtitle,
  items,
  initialSelectedId,
  descriptionMode = "all",
  onBack,
  className,
}: MenuCarouselProps) {
  const buttonRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const itemIds = useMemo(() => items.map((item) => item.id).join("|"), [items]);
  const [selectedIndex, setSelectedIndex] = useState(() =>
    getInitialSelectedIndex(items, initialSelectedId)
  );

  useEffect(() => {
    setSelectedIndex((currentIndex) => {
      if (items[currentIndex] && !items[currentIndex].disabled) return currentIndex;
      return getInitialSelectedIndex(items, initialSelectedId);
    });
  }, [initialSelectedId, itemIds, items]);

  const selectedItem = selectedIndex >= 0 ? items[selectedIndex] : undefined;

  const selectEnabledSibling = (step: 1 | -1) => {
    const currentIndex = selectedIndex >= 0 ? selectedIndex : 0;
    const nextIndex = findEnabledIndex(items, currentIndex, step);

    if (nextIndex >= 0) {
      setSelectedIndex(nextIndex);
      requestAnimationFrame(() => buttonRefs.current[nextIndex]?.focus());
    }
  };

  const activateSelectedItem = () => {
    if (!selectedItem || selectedItem.disabled) return;
    selectedItem.onSelect?.();
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    switch (event.key) {
      case "ArrowUp":
        event.preventDefault();
        selectEnabledSibling(-1);
        break;
      case "ArrowDown":
        event.preventDefault();
        selectEnabledSibling(1);
        break;
      case "Enter":
        event.preventDefault();
        activateSelectedItem();
        break;
      case "Escape":
        if (onBack) {
          event.preventDefault();
          onBack();
        }
        break;
      default:
        break;
    }
  };

  return (
    <section
      className={["menu-carousel", className].filter(Boolean).join(" ")}
      aria-label={title ?? "Menu carousel"}
      onKeyDown={handleKeyDown}
    >
      {(title || subtitle) && (
        <header className="menu-carousel__header">
          {title && <h2>{title}</h2>}
          {subtitle && <p>{subtitle}</p>}
        </header>
      )}

      <div className="menu-carousel__items" role="list">
        {items.map((item, index) => {
          const selected = index === selectedIndex;
          const offset = selectedIndex >= 0 ? index - selectedIndex : 0;
          const distance = Math.min(Math.abs(offset), 4);
          const descriptionId = `${item.id}-menu-carousel-description`;
          const descriptionText = item.disabled && item.disabledReason
            ? item.disabledReason
            : item.description;
          const showDescription =
            Boolean(descriptionText) &&
            (descriptionMode === "all" || selected);

          return (
            <button
              key={item.id}
              ref={(element) => {
                buttonRefs.current[index] = element;
              }}
              type="button"
              className={[
                "menu-carousel__item",
                selected ? "menu-carousel__item--selected" : "",
                item.disabled ? "menu-carousel__item--disabled" : "",
                item.id === "back" ? "menu-carousel__item--utility" : "",
              ].filter(Boolean).join(" ")}
              style={{ "--menu-carousel-distance": distance } as CSSProperties}
              tabIndex={selected ? 0 : -1}
              aria-current={selected ? "true" : undefined}
              aria-disabled={item.disabled ? "true" : undefined}
              aria-describedby={showDescription ? descriptionId : undefined}
              onFocus={() => setSelectedIndex(index)}
              onMouseEnter={() => setSelectedIndex(index)}
              onClick={() => {
                if (!item.disabled) item.onSelect?.();
              }}
            >
              <span className="menu-carousel__item-main">
                <span className="menu-carousel__selector" aria-hidden="true">
                  {selected ? ">" : ""}
                </span>
                <span className="menu-carousel__label">{item.label}</span>
                {item.badge && <span className="menu-carousel__badge">{item.badge}</span>}
              </span>

              {showDescription && (
                <span id={descriptionId} className="menu-carousel__description">
                  {descriptionText}
                </span>
              )}
            </button>
          );
        })}
      </div>
    </section>
  );
}
