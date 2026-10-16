'use client';
import { cn } from '@/lib/utils';
import { motion, useSpring, useTransform } from 'motion/react';
import { useEffect } from 'react';

export type AnimatedNumberProps = {
  value: number;
  className?: string;
  springOptions?: {
    bounce?: number;
    duration?: number;
    stiffness?: number;
    damping?: number;
  };
  as?: React.ElementType;
  precision?: number;
};

export function AnimatedNumber({
  value,
  className,
  springOptions = { bounce: 0, duration: 800 },
  as: Component = 'span',
  precision = 0,
}: AnimatedNumberProps) {
  const spring = useSpring(value, springOptions);
  const display = useTransform(spring, (current) => current.toFixed(precision));

  useEffect(() => {
    spring.set(value);
  }, [spring, value]);

  return (
    <Component className={cn('tabular-nums font-mono', className)}>
      <motion.span>{display}</motion.span>
    </Component>
  );
}
