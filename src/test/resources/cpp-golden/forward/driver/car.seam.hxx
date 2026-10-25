// The seam: the one factory Kira calls to get a Car. The real Car's constructor
// wants the process's argc and argv, which kira::rt::runMain kept.
#pragma once

#include "car.hxx"
#include "kira/main.hxx"

namespace bibo
{
  [[nodiscard]] inline kira::Rc<Car> openCar()
  {
      return std::make_shared<Car>(kira::rt::argc(), kira::rt::argv());
  }
}
