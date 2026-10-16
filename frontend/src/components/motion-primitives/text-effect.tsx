'use client';
import React from 'react';
import { motion, TargetAndTransition, Transition, Variant } from 'motion/react';
import { cn } from '@/lib/utils';

type TextEffectProps = {
  children: string;
  per?: 'word' | 'char' | 'line';
  as?: keyof React.JSX.IntrinsicElements;
  variants?: {
    container?: Variant;
    item?: Variant;
  };
  className?: string;
  preset?: 'fade' | 'slide' | 'fade-in-blur';
  delay?: number;
  trigger?: boolean;
  onAnimationComplete?: () => void;
};

const defaultContainerVariants = {
  hidden: { opacity: 0 },
  visible: {
    opacity: 1,
    transition: {
      staggerChildren: 0.05,
    },
  },
};

const defaultItemVariants = {
  hidden: { opacity: 0, y: 12 },
  visible: {
    opacity: 1,
    y: 0,
    transition: {
      type: 'spring',
      damping: 24,
      stiffness: 280,
    },
  },
};

export function TextEffect({
  children,
  per = 'word',
  as: Component = 'p',
  variants,
  className,
  delay = 0,
  trigger = true,
  onAnimationComplete,
}: TextEffectProps) {
  const words = children.split(' ');

  const container = {
    ...defaultContainerVariants,
    visible: {
      ...defaultContainerVariants.visible,
      transition: {
        staggerChildren: per === 'char' ? 0.02 : 0.06,
        delayChildren: delay,
      },
    },
    ...variants?.container,
  };

  const item = {
    ...defaultItemVariants,
    ...variants?.item,
  };

  return (
    <Component className={className}>
      <motion.span
        initial="hidden"
        animate={trigger ? 'visible' : 'hidden'}
        variants={container}
        className="inline-block"
        onAnimationComplete={onAnimationComplete}
      >
        {words.map((word, wordIndex) => (
          <span key={wordIndex} className="inline-block whitespace-nowrap">
            {per === 'char' ? (
              word.split('').map((char, charIndex) => (
                <motion.span
                  key={charIndex}
                  variants={item}
                  className="inline-block"
                >
                  {char}
                </motion.span>
              ))
            ) : (
              <motion.span variants={item} className="inline-block">
                {word}
              </motion.span>
            )}
            {wordIndex < words.length - 1 && <span className="inline-block">&nbsp;</span>}
          </span>
        ))}
      </motion.span>
    </Component>
  );
}
