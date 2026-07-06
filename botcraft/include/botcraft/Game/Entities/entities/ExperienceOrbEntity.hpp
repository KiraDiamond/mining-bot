#pragma once

#include "botcraft/Game/Entities/entities/Entity.hpp"

namespace Botcraft
{
    class ExperienceOrbEntity : public Entity
    {
    protected:
#if PROTOCOL_VERSION < 770 /* < 1.21.5 */
        static constexpr int metadata_count = 0;
#else
        static constexpr int metadata_count = 1;
        static const std::array<std::string, metadata_count> metadata_names;

#endif
        static constexpr int hierarchy_metadata_count = Entity::metadata_count + Entity::hierarchy_metadata_count;

    public:
        ExperienceOrbEntity();
        virtual ~ExperienceOrbEntity();

        // Object related stuff
        virtual std::string GetName() const override;
        virtual EntityType GetType() const override;

        // Static stuff, for easier comparison
        static std::string GetClassName();
        static EntityType GetClassType();

#if PROTOCOL_VERSION > 769 /* > 1.21.4 */
        virtual ProtocolCraft::Json::Value Serialize() const override;

        // Metadata stuff
        virtual void SetMetadataValue(const int index, const std::any& value) override;

        int GetDataValue() const;

        void SetDataValue(const int data_value);
#endif

    protected:
        virtual double GetWidthImpl() const override;
        virtual double GetHeightImpl() const override;

    };
}
