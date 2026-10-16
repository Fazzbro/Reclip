'use client';
import {
  AnimatePresence,
  Transition,
  Variant,
  motion,
  MotionProps,
} from 'motion/react';
import { cn } from '@/lib/utils';

export type TransitionPanelProps = {
  children: React.ReactNode;
  activeIndex: number;
  ariaLive?: 'polite' | 'assertive' | 'off';
  variants?: {
    enter: Variant;
    center: Variant;
    exit: Variant;
  };
  transition?: Transition;
  custom?: any;
  className?: string;
} & MotionProps;

export function TransitionPanel({
  children,
  activeIndex,
  ariaLive = 'polite',
  variants,
  transition,
  custom,
  className,
  ...motionProps
}: TransitionPanelProps) {
  const defaultVariants = {
    enter: (direction: number) => ({
      x: direction > 0 ? 20 : -20,
      opacity: 0,
      filter: 'blur(4px)',
    }),
    center: {
      zIndex: 1,
      x: 0,
      opacity: 1,
      filter: 'blur(0px)',
    },
    exit: (direction: number) => ({
      zIndex: 0,
      x: direction < 0 ? 20 : -20,
      opacity: 0,
      filter: 'blur(4px)',
    }),
  };

  return (
    <div className={cn('relative w-full', className)} aria-live={ariaLive}>
      <AnimatePresence initial={false} custom={custom} mode="wait">
        <motion.div
          key={activeIndex}
          custom={custom}
          variants={variants || defaultVariants}
          initial="enter"
          animate="center"
          exit="exit"
          transition={
            transition || {
              type: 'spring',
              stiffness: 300,
              damping: 30,
            }
          }
          {...motionProps}
        >
          {children}
        </motion.div>
      </AnimatePresence>
    </div>
  );
}
